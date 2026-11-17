package com.rocksguard.core

import com.rocksguard.core.TestPackets.CLIENT_V4
import com.rocksguard.core.TestPackets.DNS_V4
import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.net.Checksum
import com.rocksguard.core.net.IpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketTest {
    private val query = DnsWire.buildQuery("example.com", 1, id = 0x1234)

    @Test fun parsesIpv4Udp() {
        val raw = TestPackets.ipv4Udp(CLIENT_V4, DNS_V4, 40000, 53, query)
        val p = IpPacket.parse(raw, raw.size)!!
        assertEquals(4, p.version)
        assertTrue(p.isUdp)
        assertEquals(40000, p.sourcePort)
        assertEquals(53, p.destinationPort)
        assertArrayEquals(query, p.udpPayload())
    }

    @Test fun ipv4ReplyHasValidChecksumsAndSwappedEndpoints() {
        val raw = TestPackets.ipv4Udp(CLIENT_V4, DNS_V4, 40000, 53, query)
        val p = IpPacket.parse(raw, raw.size)!!
        val reply = IpPacket.buildUdpReply(p, byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(Checksum.verifyIpv4Header(reply))
        val r = IpPacket.parse(reply, reply.size)!!
        assertArrayEquals(DNS_V4, r.sourceAddress)
        assertArrayEquals(CLIENT_V4, r.destinationAddress)
        assertEquals(53, r.sourcePort)
        assertEquals(40000, r.destinationPort)
        // UDP checksum over pseudo header verifies to zero
        val sum = Checksum.add(Checksum.pseudoHeader(r.sourceAddress, r.destinationAddress, 17, r.transportLength), reply, 20, r.transportLength)
        assertEquals(0, Checksum.finish(sum))
    }

    @Test fun ipv6ReplyChecksum() {
        val src = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val raw = TestPackets.ipv6Udp(src, dst, 5555, 53, query)
        val p = IpPacket.parse(raw, raw.size)!!
        assertEquals(6, p.version)
        val reply = IpPacket.buildUdpReply(p, query)
        val r = IpPacket.parse(reply, reply.size)!!
        assertArrayEquals(dst, r.sourceAddress)
        val sum = Checksum.add(Checksum.pseudoHeader(r.sourceAddress, r.destinationAddress, 17, r.transportLength), reply, 40, r.transportLength)
        assertEquals(0, Checksum.finish(sum))
    }

    @Test fun rejectsFragmentsAndGarbage() {
        val raw = TestPackets.ipv4Udp(CLIENT_V4, DNS_V4, 1, 53, query)
        raw[6] = 0x20 // More Fragments
        assertNull(IpPacket.parse(raw, raw.size))
        assertNull(IpPacket.parse(ByteArray(10), 10))
        val truncated = TestPackets.ipv4Udp(CLIENT_V4, DNS_V4, 1, 53, query)
        assertNull(IpPacket.parse(truncated, 25))
    }

    @Test fun tcpResetForSyn() {
        val syn = TestPackets.ipv4Tcp(CLIENT_V4, byteArrayOf(93, 184.toByte(), 216.toByte(), 34), 43210, 443, seq = 1000, ack = 0, flags = IpPacket.TCP_SYN)
        val p = IpPacket.parse(syn, syn.size)!!
        val rst = IpPacket.buildTcpReset(p)!!
        val r = IpPacket.parse(rst, rst.size)!!
        assertEquals(IpPacket.TCP_RST or IpPacket.TCP_ACK, r.tcpFlags)
        assertEquals(1001L, r.tcpAck)
        assertEquals(0L, r.tcpSeq)
        assertEquals(443, r.sourcePort)
        assertTrue(Checksum.verifyIpv4Header(rst))
        val sum = Checksum.add(Checksum.pseudoHeader(r.sourceAddress, r.destinationAddress, 6, 20), rst, 20, 20)
        assertEquals(0, Checksum.finish(sum))
    }

    @Test fun tcpResetForAckUsesAckAsSeq() {
        val ack = TestPackets.ipv4Tcp(CLIENT_V4, DNS_V4, 1, 53, seq = 5, ack = 777, flags = IpPacket.TCP_ACK)
        val r = IpPacket.parse(IpPacket.buildTcpReset(IpPacket.parse(ack, ack.size)!!)!!, 40)!!
        assertEquals(IpPacket.TCP_RST, r.tcpFlags)
        assertEquals(777L, r.tcpSeq)
    }

    @Test fun neverAnswersReset() {
        val rst = TestPackets.ipv4Tcp(CLIENT_V4, DNS_V4, 1, 53, 1, 1, IpPacket.TCP_RST)
        assertNull(IpPacket.buildTcpReset(IpPacket.parse(rst, rst.size)!!))
        assertNotNull(IpPacket.parse(rst, rst.size))
    }
}
