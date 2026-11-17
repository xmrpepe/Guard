package com.rocksguard.core.dns

/** A parsed DNS query (only single-question queries are accepted, as per practice). */
class DnsQuery(
    val id: Int,
    val flags: Int,
    val name: String,
    val type: Int,
    val qclass: Int,
    /** Offset just past the question section. */
    val questionEnd: Int,
    /** Requestor's advertised EDNS(0) UDP payload size, or 512 without EDNS. */
    val maxUdpSize: Int,
    /** DNSSEC OK bit from EDNS. Part of the cache key because it changes the answer. */
    val dnssecOk: Boolean,
) {
    val checkingDisabled get() = flags and 0x0010 != 0
}

enum class BlockResponse { NULL_IP, NXDOMAIN, REFUSED }

object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41
    const val SVCB = 64
    const val HTTPS = 65

    fun name(type: Int): String = when (type) {
        A -> "A"; NS -> "NS"; CNAME -> "CNAME"; SOA -> "SOA"; PTR -> "PTR"; MX -> "MX"
        TXT -> "TXT"; AAAA -> "AAAA"; SRV -> "SRV"; SVCB -> "SVCB"; HTTPS -> "HTTPS"
        else -> "TYPE$type"
    }
}

class DnsFormatException(message: String) : Exception(message)

/**
 * DNS message utilities (RFC 1035) operating directly on wire bytes. Everything is
 * bounds-checked: malformed packets from the tunnel or from a resolver throw
 * [DnsFormatException] instead of crashing or reading out of range.
 */
object DnsWire {
    const val HEADER = 12
    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5
    private const val MAX_NAME = 255

    fun id(msg: ByteArray) = u16(msg, 0)
    fun rcode(msg: ByteArray) = u16(msg, 2) and 0x000F
    fun isResponse(msg: ByteArray) = msg.size >= HEADER && u16(msg, 2) and 0x8000 != 0
    fun setId(msg: ByteArray, id: Int) = put16(msg, 0, id)

    fun parseQuery(msg: ByteArray, len: Int = msg.size): DnsQuery? = try {
        parseQueryOrThrow(msg, len)
    } catch (_: DnsFormatException) {
        null
    }

    private fun parseQueryOrThrow(msg: ByteArray, len: Int): DnsQuery? {
        if (len < HEADER) return null
        val flags = u16(msg, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if ((flags ushr 11) and 0xF != 0) return null // only standard QUERY opcode
        if (u16(msg, 4) != 1) return null
        val (name, after) = readName(msg, HEADER, len)
        if (after + 4 > len) throw DnsFormatException("truncated question")
        val type = u16(msg, after)
        val qclass = u16(msg, after + 2)
        val qEnd = after + 4
        // Look for an OPT record in the additional section for EDNS size / DO bit.
        var maxUdp = 512
        var dnssecOk = false
        val an = u16(msg, 6)
        val ns = u16(msg, 8)
        val ar = u16(msg, 10)
        var off = qEnd
        repeat(an + ns + ar) { index ->
            off = skipName(msg, off, len)
            if (off + 10 > len) throw DnsFormatException("truncated rr")
            val rType = u16(msg, off)
            val rdLen = u16(msg, off + 8)
            if (rType == DnsType.OPT && index >= an + ns) {
                maxUdp = u16(msg, off + 2).coerceAtLeast(512)
                dnssecOk = u16(msg, off + 6) and 0x8000 != 0
            }
            off += 10 + rdLen
            if (off > len) throw DnsFormatException("truncated rdata")
        }
        return DnsQuery(u16(msg, 0), flags, name, type, qclass, qEnd, maxUdp, dnssecOk)
    }

    /** Reads a (possibly compressed) domain name. Returns lowercase name without trailing dot. */
    fun readName(msg: ByteArray, start: Int, len: Int = msg.size): Pair<String, Int> {
        val sb = StringBuilder(64)
        var off = start
        var end = -1
        var jumps = 0
        while (true) {
            if (off >= len) throw DnsFormatException("name overrun")
            val b = msg[off].toInt() and 0xFF
            when {
                b == 0 -> {
                    if (end < 0) end = off + 1
                    break
                }
                b and 0xC0 == 0xC0 -> {
                    if (off + 1 >= len) throw DnsFormatException("pointer overrun")
                    if (end < 0) end = off + 2
                    val ptr = ((b and 0x3F) shl 8) or (msg[off + 1].toInt() and 0xFF)
                    if (++jumps > 32 || ptr >= len) throw DnsFormatException("pointer loop")
                    off = ptr
                }
                b and 0xC0 != 0 -> throw DnsFormatException("bad label type")
                else -> {
                    if (off + 1 + b > len) throw DnsFormatException("label overrun")
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in off + 1..off + b) {
                        val c = msg[i].toInt() and 0xFF
                        sb.append(if (c in 'A'.code..'Z'.code) (c + 32).toChar() else c.toChar())
                    }
                    if (sb.length > MAX_NAME) throw DnsFormatException("name too long")
                    off += 1 + b
                }
            }
        }
        return sb.toString() to end
    }

    private fun skipName(msg: ByteArray, start: Int, len: Int): Int {
        var off = start
        while (true) {
            if (off >= len) throw DnsFormatException("name overrun")
            val b = msg[off].toInt() and 0xFF
            when {
                b == 0 -> return off + 1
                b and 0xC0 == 0xC0 -> return off + 2
                b and 0xC0 != 0 -> throw DnsFormatException("bad label type")
                else -> off += 1 + b
            }
        }
    }

    /**
     * Builds the answer RocksGuard returns for a blocked name. NULL_IP answers A/AAAA with
     * the unspecified address (apps fail fast, nothing is contacted) and returns an empty
     * NOERROR for other types, so e.g. HTTPS records fall back to the null A record.
     */
    fun buildBlockedResponse(query: ByteArray, q: DnsQuery, mode: BlockResponse, ttl: Int = 60): ByteArray {
        val rcode = when (mode) {
            BlockResponse.NULL_IP -> RCODE_NOERROR
            BlockResponse.NXDOMAIN -> RCODE_NXDOMAIN
            BlockResponse.REFUSED -> RCODE_REFUSED
        }
        val answerLen = when {
            mode != BlockResponse.NULL_IP -> 0
            q.type == DnsType.A -> 4
            q.type == DnsType.AAAA -> 16
            else -> 0
        }
        val hasAnswer = answerLen > 0
        val out = ByteArray(q.questionEnd + if (hasAnswer) 12 + answerLen else 0)
        System.arraycopy(query, 0, out, 0, q.questionEnd)
        // QR=1, keep opcode + RD, set RA.
        put16(out, 2, 0x8000 or (q.flags and 0x7900) or 0x0080 or rcode)
        put16(out, 4, 1)
        put16(out, 6, if (hasAnswer) 1 else 0)
        put16(out, 8, 0)
        put16(out, 10, 0)
        if (hasAnswer) {
            var o = q.questionEnd
            put16(out, o, 0xC00C); o += 2
            put16(out, o, q.type); o += 2
            put16(out, o, 1); o += 2
            put32(out, o, ttl.toLong()); o += 4
            put16(out, o, answerLen) // rdata is all-zero: 0.0.0.0 / ::
        }
        return out
    }

    /** A response with only the header + question and the given rcode (e.g. SERVFAIL). */
    fun buildErrorResponse(query: ByteArray, q: DnsQuery, rcode: Int): ByteArray {
        val out = query.copyOf(q.questionEnd)
        put16(out, 2, 0x8000 or (q.flags and 0x7900) or 0x0080 or (rcode and 0xF))
        put16(out, 6, 0); put16(out, 8, 0); put16(out, 10, 0)
        return out
    }

    /** Truncated (TC=1) form of a response that does not fit the requestor's UDP size. */
    fun truncate(response: ByteArray, q: DnsQuery): ByteArray {
        val out = response.copyOf(minOf(q.questionEnd, response.size))
        put16(out, 2, u16(response, 2) or 0x0200)
        put16(out, 6, 0); put16(out, 8, 0); put16(out, 10, 0)
        return out
    }

    /** Returns every CNAME target in the answer section (lowercased). */
    fun answerCnames(msg: ByteArray): List<String> {
        val result = ArrayList<String>(2)
        walkRecords(msg) { section, type, _, rdOff, _ ->
            if (section == 0 && type == DnsType.CNAME) result += readName(msg, rdOff).first
            true
        }
        return result
    }

    /**
     * Cache lifetime for a response: the smallest answer TTL; for negative answers the SOA
     * negative-caching TTL (RFC 2308). Returns 0 when the response should not be cached.
     */
    fun cacheTtl(msg: ByteArray): Int {
        if (msg.size < HEADER) return 0
        val rcode = rcode(msg)
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return 0
        if (u16(msg, 2) and 0x0200 != 0) return 0 // truncated
        var min = Long.MAX_VALUE
        var soa = Long.MAX_VALUE
        walkRecords(msg) { section, type, ttlOff, rdOff, rdLen ->
            val ttl = u32(msg, ttlOff)
            when {
                section == 0 -> min = minOf(min, ttl)
                section == 1 && type == DnsType.SOA && rdLen >= 22 -> {
                    soa = minOf(ttl, u32(msg, rdOff + rdLen - 4))
                }
            }
            true
        }
        val v = when {
            min != Long.MAX_VALUE -> min
            soa != Long.MAX_VALUE -> soa
            else -> 0
        }
        return v.coerceIn(0, 86_400).toInt()
    }

    /** Decrements every RR TTL (except OPT) by [elapsedSeconds], flooring at zero. */
    fun ageTtls(msg: ByteArray, elapsedSeconds: Long) {
        if (elapsedSeconds <= 0) return
        walkRecords(msg) { _, type, ttlOff, _, _ ->
            if (type != DnsType.OPT) put32(msg, ttlOff, (u32(msg, ttlOff) - elapsedSeconds).coerceAtLeast(0))
            true
        }
    }

    /** Extracts A/AAAA addresses from the answer section. */
    fun answerAddresses(msg: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        walkRecords(msg) { section, type, _, rdOff, rdLen ->
            if (section == 0 && ((type == DnsType.A && rdLen == 4) || (type == DnsType.AAAA && rdLen == 16))) {
                out += msg.copyOfRange(rdOff, rdOff + rdLen)
            }
            true
        }
        return out
    }

    /**
     * Visits every resource record. Callback gets (section 0=answer 1=authority 2=additional,
     * type, ttlOffset, rdataOffset, rdLength) and returns false to stop.
     */
    inline fun walkRecords(msg: ByteArray, visit: (Int, Int, Int, Int, Int) -> Boolean) {
        val len = msg.size
        if (len < HEADER) throw DnsFormatException("short message")
        val qd = u16(msg, 4)
        val counts = intArrayOf(u16(msg, 6), u16(msg, 8), u16(msg, 10))
        var off = HEADER
        repeat(qd) { off = skipNamePublic(msg, off, len) + 4 }
        for (section in 0..2) {
            repeat(counts[section]) {
                off = skipNamePublic(msg, off, len)
                if (off + 10 > len) throw DnsFormatException("truncated rr")
                val type = u16(msg, off)
                val rdLen = u16(msg, off + 8)
                val rdOff = off + 10
                if (rdOff + rdLen > len) throw DnsFormatException("truncated rdata")
                if (!visit(section, type, off + 4, rdOff, rdLen)) return
                off = rdOff + rdLen
            }
        }
    }

    @PublishedApi
    internal fun skipNamePublic(msg: ByteArray, start: Int, len: Int) = skipName(msg, start, len)

    // ---- Query builder (used for resolver health checks and tests) --------------------
    fun buildQuery(name: String, type: Int, id: Int = 0, recursionDesired: Boolean = true): ByteArray {
        val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
        val nameLen = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(HEADER + nameLen + 4)
        put16(out, 0, id)
        put16(out, 2, if (recursionDesired) 0x0100 else 0)
        put16(out, 4, 1)
        var o = HEADER
        for (l in labels) {
            require(l.length in 1..63) { "bad label" }
            out[o++] = l.length.toByte()
            for (c in l) out[o++] = c.code.toByte()
        }
        out[o++] = 0
        put16(out, o, type); put16(out, o + 2, 1)
        return out
    }

    @PublishedApi
    internal fun u16(b: ByteArray, o: Int): Int {
        if (o + 1 >= b.size || o < 0) throw DnsFormatException("u16 overrun")
        return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }

    @PublishedApi
    internal fun u32(b: ByteArray, o: Int): Long {
        if (o + 3 >= b.size || o < 0) throw DnsFormatException("u32 overrun")
        return ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    }

    internal fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    internal fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
