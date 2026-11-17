package com.rocksguard.core.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.Reader

/** Describes one compiled filter list so verdicts can explain themselves. */
data class SourceInfo(val id: String, val name: String, val category: Category)

/** Why a name was allowed or blocked. Every block carries a human explanation. */
sealed class Verdict {
    abstract val blocked: Boolean

    data object Allowed : Verdict() { override val blocked = false }

    data class AllowedByUser(val rule: String) : Verdict() { override val blocked = false }

    data class AllowedByListException(val rule: String, val source: SourceInfo?) : Verdict() {
        override val blocked = false
    }

    data class BlockedByList(
        val matched: String,
        val category: Category,
        val source: SourceInfo?,
        val viaSubdomainRule: Boolean,
    ) : Verdict() { override val blocked = true }

    data class BlockedByUser(val rule: String) : Verdict() { override val blocked = true }

    /** The query was blocked because the app making it is denied by the Ramparts. */
    data class BlockedByFirewall(val uid: Int) : Verdict() { override val blocked = true }

    /** The name itself was clean but its CNAME chain pointed at a blocked name. */
    data class BlockedCname(val cname: String, val inner: Verdict) : Verdict() { override val blocked = true }

    /** A canary domain answered NXDOMAIN to keep apps on the system resolver. */
    data class BlockedCanary(val reason: String) : Verdict() { override val blocked = true }

    /** Category used for statistics. */
    val statCategory: Category
        get() = when (this) {
            is BlockedByList -> category
            is BlockedCname -> (inner as? BlockedByList)?.category ?: Category.TRACKERS
            is BlockedCanary -> Category.BYPASS
            else -> Category.NONE
        }
}

/**
 * Immutable snapshot of every rule in force. A new snapshot is swapped in atomically
 * whenever lists or rules change, so the packet path never takes a lock.
 */
class FilterSnapshot(
    val lists: CompiledLists,
    private val userBlock: CompactDomainSet,
    private val userAllow: CompactDomainSet,
    /** Categories the user has switched on in The Shield. */
    val enabledCategories: Set<Category>,
) {
    private val block get() = lists.block
    private val allow get() = lists.allow
    val sources get() = lists.sources
    val blockedDomainCount get() = lists.block.size
    val userRuleCount get() = userBlock.size + userAllow.size

    fun decide(name: String): Verdict {
        if (name.isEmpty()) return Verdict.Allowed
        // Suffix start offsets, most specific first: a.b.c → [0, 2, 4]
        val starts = IntArray(MAX_LABELS)
        var n = 0
        starts[n++] = 0
        var i = 0
        while (i < name.length && n < MAX_LABELS) {
            if (name[i] == '.' && i + 1 < name.length) starts[n++] = i + 1
            i++
        }
        val hashes = LongArray(n) { DomainHash.hash(name, starts[it], name.length) }

        // 1. User allow rules win over everything (domain and its subdomains).
        for (k in 0 until n) if (userAllow.lookup(hashes[k]) != CompactDomainSet.NOT_FOUND) {
            return Verdict.AllowedByUser(name.substring(starts[k]))
        }
        // 2. User block rules.
        for (k in 0 until n) if (userBlock.lookup(hashes[k]) != CompactDomainSet.NOT_FOUND) {
            return Verdict.BlockedByUser(name.substring(starts[k]))
        }
        // 3. Exceptions published by the lists themselves.
        for (k in 0 until n) {
            val p = allow.lookup(hashes[k])
            if (p != CompactDomainSet.NOT_FOUND && applies(p, k)) {
                return Verdict.AllowedByListException(name.substring(starts[k]), sources.getOrNull(Rule.source(p)))
            }
        }
        // 4. List blocks, most specific match first.
        for (k in 0 until n) {
            val p = block.lookup(hashes[k])
            if (p != CompactDomainSet.NOT_FOUND && applies(p, k) && Rule.category(p) in enabledCategories) {
                return Verdict.BlockedByList(
                    matched = name.substring(starts[k]),
                    category = Rule.category(p),
                    source = sources.getOrNull(Rule.source(p)),
                    viaSubdomainRule = k > 0,
                )
            }
        }
        return Verdict.Allowed
    }

    private fun applies(payload: Int, suffixIndex: Int) =
        if (suffixIndex == 0) Rule.exact(payload) else Rule.subdomains(payload)

    companion object {
        private const val MAX_LABELS = 32
        val EMPTY = FilterSnapshot(
            CompiledLists.EMPTY,
            CompactDomainSet.EMPTY, CompactDomainSet.EMPTY, Category.entries.toSet(),
        )
    }
}

/** Every subscribed list compiled into one structure. Serialisable for fast start-up. */
class CompiledLists(
    val block: CompactDomainSet,
    val allow: CompactDomainSet,
    val sources: List<SourceInfo>,
) {
    fun writeTo(out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeInt(sources.size)
        for (s in sources) {
            out.writeUTF(s.id); out.writeUTF(s.name); out.writeUTF(s.category.name)
        }
        block.writeTo(out)
        allow.writeTo(out)
    }

    fun withSnapshot(
        userBlocked: Collection<String>,
        userAllowed: Collection<String>,
        enabledCategories: Set<Category>,
    ) = FilterSnapshot(this, FilterCompiler.userSet(userBlocked), FilterCompiler.userSet(userAllowed), enabledCategories)

    companion object {
        private const val VERSION = 1
        val EMPTY = CompiledLists(CompactDomainSet.EMPTY, CompactDomainSet.EMPTY, emptyList())

        fun readFrom(input: DataInputStream): CompiledLists {
            if (input.readInt() != VERSION) throw IOException("version")
            val n = input.readInt()
            if (n !in 0..254) throw IOException("sources")
            val sources = List(n) {
                SourceInfo(input.readUTF(), input.readUTF(), Category.valueOf(input.readUTF()))
            }
            return CompiledLists(CompactDomainSet.readFrom(input), CompactDomainSet.readFrom(input), sources)
        }
    }
}

/** Builds [CompiledLists] from list text. */
class FilterCompiler {
    private val block = CompactDomainSetBuilder(64 * 1024)
    private val allow = CompactDomainSetBuilder(256)
    private val sources = ArrayList<SourceInfo>()

    /** Adds a list; returns the number of rules accepted. */
    fun addList(info: SourceInfo, reader: Reader): Int {
        require(sources.size < Rule.SOURCE_USER) { "too many lists" }
        val index = sources.size
        sources += info
        val stats = FilterParser.Stats()
        FilterParser.parse(reader, stats) { e ->
            val payload = Rule.pack(index, info.category, e.exact, e.subdomains)
            if (e.exception) allow.add(e.domain, payload) else block.add(e.domain, payload)
        }
        return stats.accepted
    }

    fun build(): CompiledLists = CompiledLists(block.build(), allow.build(), sources.toList())

    companion object {
        fun userSet(domains: Collection<String>): CompactDomainSet {
            val b = CompactDomainSetBuilder(domains.size.coerceAtLeast(4))
            for (d in domains) FilterParser.normalise(d.removePrefix("*."))?.let {
                b.add(it, Rule.pack(Rule.SOURCE_USER, Category.NONE, exact = true, subdomains = true))
            }
            return b.build()
        }
    }
}
