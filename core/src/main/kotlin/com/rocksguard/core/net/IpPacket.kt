package com.rocksguard.core.net

/**
 * Minimal, allocation-light parser and builder for the IPv4/IPv6 packets that arrive
 * on the VPN tunnel. Only what RocksGuard needs is supported: UDP (DNS) and TCP
 * (so that blocked connections can be refused immediately with a RST instead of
 * hanging until the app times out).
 *
 * Fragmented packets are rejected (null) rather than half-parsed.
 */
class IpPacket private constructor(
    val buffer: ByteArray,
    val length: Int,
    val version: Int,
    val protocol: Int,
    /** Offset of the transport (UDP/TCP) header. */
    val transportOffset: Int,
    /** Length of transport header + payload. */
    val transportLength: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
) {
    val isUdp get() = protocol == PROTO_UDP
    val isTcp get() = protocol == PROTO_TCP

    val sourcePort: Int get() = u16(buffer, transportOffset)
    val destinationPort: Int get() = u16(buffer, transportOffset + 2)

    /** UDP payload start (only valid when [isUdp]). */
    val udpPayloadOffset get() = transportOffset + UDP_HEADER
    val udpPayloadLength: Int
        get() = (u16(buffer, transportOffset + 4) - UDP_HEADER).coerceIn(0, transportLength - UDP_HEADER)

    fun udpPayload(): ByteArray = buffer.copyOfRange(udpPayloadOffset, udpPayloadOffset + udpPayloadLength)

    // ---- TCP accessors -------------------------------------------------------------
    val tcpSeq: Long get() = u32(buffer, transportOffset + 4)
    val tcpAck: Long get() = u32(buffer, transportOffset + 8)
    val tcpFlags: Int get() = buffer[transportOffset + 13].toInt() and 0x3F
    val tcpHeaderLength: Int get() = ((buffer[transportOffset + 12].toInt() and 0xF0) ushr 4) * 4
    val tcpPayloadLength: Int get() = (transportLength - tcpHeaderLength).coerceAtLeast(0)

    companion object {
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
        const val UDP_HEADER = 8
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_ACK = 0x10

        fun parse(buf: ByteArray, len: Int): IpPacket? {
            if (len < 20) return null
            return when ((buf[0].toInt() and 0xF0) ushr 4) {
                4 -> parseV4(buf, len)
                6 -> parseV6(buf, len)
                else -> null
            }
        }

        private fun parseV4(buf: ByteArray, len: Int): IpPacket? {
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (ihl < 20 || len < ihl) return null
            val total = u16(buf, 2)
            if (total < ihl || total > len) return null
            val fragField = u16(buf, 6)
            val moreFragments = fragField and 0x2000 != 0
            val fragOffset = fragField and 0x1FFF
            if (moreFragments || fragOffset != 0) return null
            val proto = buf[9].toInt() and 0xFF
            val tLen = total - ihl
            if (!transportFits(proto, buf, ihl, tLen)) return null
            return IpPacket(
                buf, total, 4, proto, ihl, tLen,
                buf.copyOfRange(12, 16), buf.copyOfRange(16, 20),
            )
        }

        private fun parseV6(buf: ByteArray, len: Int): IpPacket? {
            if (len < 40) return null
            val payloadLen = u16(buf, 4)
            val total = 40 + payloadLen
            if (total > len) return null
            var next = buf[6].toInt() and 0xFF
            var off = 40
            // Walk the extension headers we can safely skip.
            var guard = 0
            while (next == 0 || next == 43 || next == 60) {
                if (off + 8 > total || guard++ > 8) return null
                val extLen = ((buf[off + 1].toInt() and 0xFF) + 1) * 8
                next = buf[off].toInt() and 0xFF
                off += extLen
            }
            if (next == 44) return null // fragment header: not supported
            val tLen = total - off
            if (!transportFits(next, buf, off, tLen)) return null
            return IpPacket(
                buf, total, 6, next, off, tLen,
                buf.copyOfRange(8, 24), buf.copyOfRange(24, 40),
            )
        }

        private fun transportFits(proto: Int, buf: ByteArray, off: Int, tLen: Int): Boolean = when (proto) {
            PROTO_UDP -> tLen >= UDP_HEADER && u16(buf, off + 4) in UDP_HEADER..tLen
            PROTO_TCP -> tLen >= 20 && ((buf[off + 12].toInt() and 0xF0) ushr 4) * 4 in 20..tLen
            else -> true
        }

        // ---- Builders ------------------------------------------------------------------

        /** Builds a UDP datagram that answers [request] (addresses and ports swapped). */
        fun buildUdpReply(request: IpPacket, payload: ByteArray, payloadLen: Int = payload.size): ByteArray {
            val udpLen = UDP_HEADER + payloadLen
            val src = request.destinationAddress
            val dst = request.sourceAddress
            val ipHeader = if (request.version == 4) 20 else 40
            val out = ByteArray(ipHeader + udpLen)
            writeIpHeader(out, request.version, PROTO_UDP, src, dst, udpLen)
            val t = ipHeader
            put16(out, t, request.destinationPort)
            put16(out, t + 2, request.sourcePort)
            put16(out, t + 4, udpLen)
            System.arraycopy(payload, 0, out, t + UDP_HEADER, payloadLen)
            var sum = Checksum.pseudoHeader(src, dst, PROTO_UDP, udpLen)
            sum = Checksum.add(sum, out, t, udpLen)
            var c = Checksum.finish(sum)
            if (c == 0) c = 0xFFFF // RFC 768: transmitted all-ones when computed zero
            put16(out, t + 6, c)
            return out
        }

        /**
         * Builds a TCP RST answering [request] following RFC 9293 §3.10.7.1, so a blocked
         * app fails instantly instead of retrying a silent black hole (saves battery).
         * Returns null when the request itself is a RST (never answer a RST).
         */
        fun buildTcpReset(request: IpPacket): ByteArray? {
            if (!request.isTcp) return null
            val flags = request.tcpFlags
            if (flags and TCP_RST != 0) return null
            val src = request.destinationAddress
            val dst = request.sourceAddress
            val ipHeader = if (request.version == 4) 20 else 40
            val out = ByteArray(ipHeader + 20)
            writeIpHeader(out, request.version, PROTO_TCP, src, dst, 20)
            val t = ipHeader
            put16(out, t, request.destinationPort)
            put16(out, t + 2, request.sourcePort)
            val seq: Long
            val ack: Long
            val outFlags: Int
            if (flags and TCP_ACK != 0) {
                seq = request.tcpAck
                ack = 0
                outFlags = TCP_RST
            } else {
                var segLen = request.tcpPayloadLength.toLong()
                if (flags and TCP_SYN != 0) segLen++
                if (flags and TCP_FIN != 0) segLen++
                seq = 0
                ack = (request.tcpSeq + segLen) and 0xFFFFFFFFL
                outFlags = TCP_RST or TCP_ACK
            }
            put32(out, t + 4, seq)
            put32(out, t + 8, ack)
            out[t + 12] = (5 shl 4).toByte()
            out[t + 13] = outFlags.toByte()
            // window, checksum, urgent pointer stay 0 until checksum is computed
            var sum = Checksum.pseudoHeader(src, dst, PROTO_TCP, 20)
            sum = Checksum.add(sum, out, t, 20)
            put16(out, t + 16, Checksum.finish(sum))
            return out
        }

        private var ipId = 0

        private fun writeIpHeader(out: ByteArray, version: Int, proto: Int, src: ByteArray, dst: ByteArray, transportLen: Int) {
            if (version == 4) {
                out[0] = 0x45
                put16(out, 2, 20 + transportLen)
                put16(out, 4, (ipId++) and 0xFFFF)
                put16(out, 6, 0x4000) // Don't Fragment
                out[8] = 64
                out[9] = proto.toByte()
                System.arraycopy(src, 0, out, 12, 4)
                System.arraycopy(dst, 0, out, 16, 4)
                put16(out, 10, Checksum.finish(Checksum.add(0, out, 0, 20)))
            } else {
                out[0] = 0x60
                put16(out, 4, transportLen)
                out[6] = proto.toByte()
                out[7] = 64
                System.arraycopy(src, 0, out, 8, 16)
                System.arraycopy(dst, 0, out, 24, 16)
            }
        }

        internal fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        internal fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

        internal fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
        }

        internal fun put32(b: ByteArray, o: Int, v: Long) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }
    }
}

/** RFC 1071 Internet checksum helpers. */
object Checksum {
    fun add(initial: Long, data: ByteArray, offset: Int, length: Int): Long {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        return sum
    }

    fun pseudoHeader(src: ByteArray, dst: ByteArray, proto: Int, length: Int): Long {
        var sum = add(0, src, 0, src.size)
        sum = add(sum, dst, 0, dst.size)
        sum += proto
        sum += length.toLong() and 0xFFFF
        if (src.size == 16) sum += (length.toLong() ushr 16)
        return sum
    }

    fun finish(sum0: Long): Int {
        var sum = sum0
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    /** Verifies the checksum of a full IPv4 header. Used by tests. */
    fun verifyIpv4Header(buf: ByteArray): Boolean = finish(add(0, buf, 0, (buf[0].toInt() and 0x0F) * 4)) == 0
}
