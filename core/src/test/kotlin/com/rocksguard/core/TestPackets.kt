package com.rocksguard.core

import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.net.Checksum

/** Helpers for building realistic packets in tests. */
object TestPackets {
    val CLIENT_V4 = byteArrayOf(10, 111, 222.toByte(), 2)
    val DNS_V4 = byteArrayOf(10, 111, 222.toByte(), 1)

    fun ipv4Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val out = ByteArray(20 + udpLen)
        out[0] = 0x45
        put16(out, 2, out.size)
        out[8] = 64; out[9] = 17
        System.arraycopy(src, 0, out, 12, 4); System.arraycopy(dst, 0, out, 16, 4)
        put16(out, 10, Checksum.finish(Checksum.add(0, out, 0, 20)))
        put16(out, 20, srcPort); put16(out, 22, dstPort); put16(out, 24, udpLen)
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    fun ipv6Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val out = ByteArray(40 + udpLen)
        out[0] = 0x60
        put16(out, 4, udpLen); out[6] = 17; out[7] = 64
        System.arraycopy(src, 0, out, 8, 16); System.arraycopy(dst, 0, out, 24, 16)
        put16(out, 40, srcPort); put16(out, 42, dstPort); put16(out, 44, udpLen)
        System.arraycopy(payload, 0, out, 48, payload.size)
        return out
    }

    fun ipv4Tcp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, payload: Int = 0): ByteArray {
        val out = ByteArray(40 + payload)
        out[0] = 0x45
        put16(out, 2, out.size)
        out[8] = 64; out[9] = 6
        System.arraycopy(src, 0, out, 12, 4); System.arraycopy(dst, 0, out, 16, 4)
        put16(out, 10, Checksum.finish(Checksum.add(0, out, 0, 20)))
        put16(out, 20, srcPort); put16(out, 22, dstPort)
        put32(out, 24, seq); put32(out, 28, ack)
        out[32] = (5 shl 4).toByte(); out[33] = flags.toByte()
        return out
    }

    class RR(val type: Int, val ttl: Long, val rdata: ByteArray, val namePointer: Int = 0x0C)

    /** Builds a response to [query] containing the given answers (names compressed). */
    fun response(query: ByteArray, answers: List<RR>, rcode: Int = 0, authoritySoaTtl: Long? = null): ByteArray {
        val q = DnsWire.parseQuery(query)!!
        val parts = java.io.ByteArrayOutputStream()
        parts.write(query, 0, q.questionEnd)
        for (rr in answers) {
            parts.write(byteArrayOf((0xC0 or (rr.namePointer shr 8)).toByte(), rr.namePointer.toByte()))
            parts.write(be16(rr.type)); parts.write(be16(1)); parts.write(be32(rr.ttl))
            parts.write(be16(rr.rdata.size)); parts.write(rr.rdata)
        }
        if (authoritySoaTtl != null) {
            // root SOA with MINIMUM = authoritySoaTtl
            val rdata = byteArrayOf(0, 0) + ByteArray(20).also { put32(it, 16, authoritySoaTtl) }
            parts.write(byteArrayOf(0)); parts.write(be16(6)); parts.write(be16(1)); parts.write(be32(authoritySoaTtl * 2))
            parts.write(be16(rdata.size)); parts.write(rdata)
        }
        val out = parts.toByteArray()
        put16(out, 2, 0x8180 or rcode)
        put16(out, 6, answers.size)
        put16(out, 8, if (authoritySoaTtl != null) 1 else 0)
        put16(out, 10, 0)
        return out
    }

    /** Encodes a domain name uncompressed (for CNAME rdata). */
    fun name(n: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (l in n.split('.')) { o.write(l.length); o.write(l.toByteArray()) }
        o.write(0)
        return o.toByteArray()
    }

    fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    fun put32(b: ByteArray, o: Int, v: Long) { b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte() }
}
