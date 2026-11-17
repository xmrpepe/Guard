package com.rocksguard.core.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** 64-bit hashing of (sub)strings of lowercase domain names without allocating. */
object DomainHash {
    fun hash(s: CharSequence, start: Int = 0, end: Int = s.length): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (i in start until end) {
            var c = s[i].code
            if (c in 'A'.code..'Z'.code) c += 32
            h = h xor c.toLong()
            h *= 0x100000001b3L
        }
        // murmur3 fmix64 for a good spread of the high bits used by binary search
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        h *= -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return h
    }
}

/**
 * Rule payload packed into an Int.
 *  bits 0-7   source index (which filter list), 0xFF = user rule
 *  bits 8-10  [Category] ordinal
 *  bit  12    rule matches the exact name
 *  bit  13    rule matches every subdomain
 */
object Rule {
    const val SOURCE_USER = 0xFF
    const val EXACT = 1 shl 12
    const val SUBDOMAINS = 1 shl 13

    fun pack(source: Int, category: Category, exact: Boolean, subdomains: Boolean): Int =
        (source and 0xFF) or (category.ordinal shl 8) or
            (if (exact) EXACT else 0) or (if (subdomains) SUBDOMAINS else 0)

    fun source(p: Int) = p and 0xFF
    fun category(p: Int) = Category.entries[(p ushr 8) and 0x7]
    fun exact(p: Int) = p and EXACT != 0
    fun subdomains(p: Int) = p and SUBDOMAINS != 0

    /** Merge two rules for the same domain: union of scope, most severe category wins. */
    fun merge(a: Int, b: Int): Int {
        val scope = (a or b) and (EXACT or SUBDOMAINS)
        val base = if (category(b).ordinal > category(a).ordinal) b else a
        return (base and (EXACT or SUBDOMAINS).inv()) or scope
    }
}

/** Blocklist categories, ordered by severity (higher ordinal wins when merged). */
enum class Category { NONE, ADS, TRACKERS, BYPASS, THREATS }

/**
 * An immutable, memory-compact set of domain rules: a sorted array of 64-bit hashes
 * with a parallel payload array. 300k domains cost ~3.6 MB, versus 30+ MB for a
 * HashSet<String>, and lookups are a binary search (≈19 probes) per label.
 *
 * Hash collisions are possible in theory (≈ n / 2^64 per lookup, i.e. never in practice).
 */
class CompactDomainSet internal constructor(
    private val hashes: LongArray,
    private val payloads: IntArray,
) {
    val size get() = hashes.size

    fun lookup(hash: Long): Int {
        var lo = 0
        var hi = hashes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = hashes[mid]
            when {
                v < hash -> lo = mid + 1
                v > hash -> hi = mid - 1
                else -> return payloads[mid]
            }
        }
        return NOT_FOUND
    }

    fun contains(domain: String) = lookup(DomainHash.hash(domain)) != NOT_FOUND

    fun writeTo(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(hashes.size)
        for (h in hashes) out.writeLong(h)
        for (p in payloads) out.writeInt(p)
    }

    companion object {
        const val NOT_FOUND = -1
        private const val MAGIC = 0x52474453 // "RGDS"
        val EMPTY = CompactDomainSet(LongArray(0), IntArray(0))

        fun readFrom(input: DataInputStream): CompactDomainSet {
            if (input.readInt() != MAGIC) throw IOException("bad magic")
            val n = input.readInt()
            if (n < 0 || n > 20_000_000) throw IOException("bad size")
            val h = LongArray(n) { input.readLong() }
            val p = IntArray(n) { input.readInt() }
            for (i in 1 until n) if (h[i - 1] >= h[i]) throw IOException("unsorted")
            return CompactDomainSet(h, p)
        }
    }
}

class CompactDomainSetBuilder(initialCapacity: Int = 1024) {
    private var hashes = LongArray(initialCapacity)
    private var payloads = IntArray(initialCapacity)
    var count = 0
        private set

    fun add(domain: CharSequence, payload: Int) = addHash(DomainHash.hash(domain), payload)

    fun addHash(hash: Long, payload: Int) {
        if (count == hashes.size) {
            val n = (hashes.size * 3 / 2).coerceAtLeast(16)
            hashes = hashes.copyOf(n); payloads = payloads.copyOf(n)
        }
        hashes[count] = hash
        payloads[count] = payload
        count++
    }

    fun build(): CompactDomainSet {
        if (count == 0) return CompactDomainSet.EMPTY
        sort(0, count - 1)
        // Deduplicate, merging payloads.
        var w = 0
        for (r in 0 until count) {
            if (w > 0 && hashes[w - 1] == hashes[r]) {
                payloads[w - 1] = Rule.merge(payloads[w - 1], payloads[r])
            } else {
                hashes[w] = hashes[r]; payloads[w] = payloads[r]; w++
            }
        }
        return CompactDomainSet(hashes.copyOf(w), payloads.copyOf(w))
    }

    // In-place dual-array quicksort (hashes are uniformly distributed so it is well behaved).
    private fun sort(lo0: Int, hi0: Int) {
        var lo = lo0
        var hi = hi0
        while (hi - lo > 16) {
            val mid = (lo + hi) ushr 1
            if (hashes[mid] < hashes[lo]) swap(mid, lo)
            if (hashes[hi] < hashes[lo]) swap(hi, lo)
            if (hashes[hi] < hashes[mid]) swap(hi, mid)
            val pivot = hashes[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (hashes[i] < pivot) i++
                while (hashes[j] > pivot) j--
                if (i <= j) { swap(i, j); i++; j-- }
            }
            // Recurse into the smaller half to bound stack depth.
            if (j - lo < hi - i) { sort(lo, j); lo = i } else { sort(i, hi); hi = j }
        }
        for (i in lo + 1..hi) {
            val h = hashes[i]; val p = payloads[i]
            var j = i - 1
            while (j >= lo && hashes[j] > h) { hashes[j + 1] = hashes[j]; payloads[j + 1] = payloads[j]; j-- }
            hashes[j + 1] = h; payloads[j + 1] = p
        }
    }

    private fun swap(a: Int, b: Int) {
        val h = hashes[a]; hashes[a] = hashes[b]; hashes[b] = h
        val p = payloads[a]; payloads[a] = payloads[b]; payloads[b] = p
    }
}
