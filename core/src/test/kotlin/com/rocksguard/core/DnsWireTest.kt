package com.rocksguard.core

import com.rocksguard.core.TestPackets.RR
import com.rocksguard.core.dns.BlockResponse
import com.rocksguard.core.dns.DnsCache
import com.rocksguard.core.dns.DnsFormatException
import com.rocksguard.core.dns.DnsType
import com.rocksguard.core.dns.DnsWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DnsWireTest {
    @Test fun parsesQueryLowercased() {
        val q = DnsWire.parseQuery(DnsWire.buildQuery("Ads.Example.COM", DnsType.AAAA, id = 7))!!
        assertEquals("ads.example.com", q.name)
        assertEquals(DnsType.AAAA, q.type)
        assertEquals(7, q.id)
        assertEquals(512, q.maxUdpSize)
    }

    @Test fun nullIpAnswerForA() {
        val raw = DnsWire.buildQuery("ads.example.com", DnsType.A, id = 99)
        val q = DnsWire.parseQuery(raw)!!
        val r = DnsWire.buildBlockedResponse(raw, q, BlockResponse.NULL_IP)
        assertTrue(DnsWire.isResponse(r))
        assertEquals(99, DnsWire.id(r))
        assertEquals(0, DnsWire.rcode(r))
        val addrs = DnsWire.answerAddresses(r)
        assertEquals(1, addrs.size)
        assertArrayEquals(ByteArray(4), addrs[0])
    }

    @Test fun nullIpAnswerForAaaaAndNodataForHttps() {
        val raw6 = DnsWire.buildQuery("ads.example.com", DnsType.AAAA)
        val r6 = DnsWire.buildBlockedResponse(raw6, DnsWire.parseQuery(raw6)!!, BlockResponse.NULL_IP)
        assertArrayEquals(ByteArray(16), DnsWire.answerAddresses(r6).single())
        val rawH = DnsWire.buildQuery("ads.example.com", DnsType.HTTPS)
        val rH = DnsWire.buildBlockedResponse(rawH, DnsWire.parseQuery(rawH)!!, BlockResponse.NULL_IP)
        assertEquals(0, DnsWire.rcode(rH))
        assertTrue(DnsWire.answerAddresses(rH).isEmpty())
    }

    @Test fun nxdomainAndRefusedModes() {
        val raw = DnsWire.buildQuery("ads.example.com", DnsType.A)
        val q = DnsWire.parseQuery(raw)!!
        assertEquals(3, DnsWire.rcode(DnsWire.buildBlockedResponse(raw, q, BlockResponse.NXDOMAIN)))
        assertEquals(5, DnsWire.rcode(DnsWire.buildBlockedResponse(raw, q, BlockResponse.REFUSED)))
    }

    @Test fun extractsCompressedCnameChain() {
        val raw = DnsWire.buildQuery("metrics.shop.example", DnsType.A)
        val cnameRdata = TestPackets.name("collector.tracker.example")
        val resp = TestPackets.response(
            raw, listOf(
                RR(DnsType.CNAME, 300, cnameRdata),
                RR(DnsType.A, 60, byteArrayOf(1, 2, 3, 4), namePointer = raw.size + 12),
            ),
        )
        assertEquals(listOf("collector.tracker.example"), DnsWire.answerCnames(resp))
        assertEquals(60, DnsWire.cacheTtl(resp))
    }

    @Test fun negativeCachingUsesSoaMinimum() {
        val raw = DnsWire.buildQuery("nope.example", DnsType.A)
        val resp = TestPackets.response(raw, emptyList(), rcode = 3, authoritySoaTtl = 120)
        assertEquals(120, DnsWire.cacheTtl(resp))
    }

    @Test fun servfailIsNotCached() {
        val raw = DnsWire.buildQuery("x.example", DnsType.A)
        assertEquals(0, DnsWire.cacheTtl(TestPackets.response(raw, emptyList(), rcode = 2)))
    }

    @Test fun rejectsResponsesAndMultiQuestion() {
        val raw = DnsWire.buildQuery("x.example", DnsType.A)
        raw[2] = (raw[2].toInt() or 0x80).toByte()
        assertNull(DnsWire.parseQuery(raw))
        val two = DnsWire.buildQuery("x.example", DnsType.A).also { it[5] = 2 }
        assertNull(DnsWire.parseQuery(two))
    }

    @Test(expected = DnsFormatException::class)
    fun compressionLoopIsDetected() {
        val msg = ByteArray(16)
        msg[12] = 0xC0.toByte(); msg[13] = 12 // pointer to itself
        DnsWire.readName(msg, 12)
    }

    @Test fun fuzzNeverThrowsUnexpected() {
        val rnd = Random(42)
        val base = TestPackets.response(DnsWire.buildQuery("a.b.example", 1), listOf(RR(1, 30, byteArrayOf(1, 1, 1, 1))))
        repeat(20_000) {
            val m = base.copyOf(rnd.nextInt(0, base.size + 8))
            repeat(rnd.nextInt(1, 6)) { if (m.isNotEmpty()) m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte() }
            DnsWire.parseQuery(m) // never throws
            try {
                DnsWire.answerCnames(m); DnsWire.cacheTtl(m); DnsWire.answerAddresses(m)
            } catch (_: DnsFormatException) {
            }
        }
    }

    @Test fun cacheAgesTtlsAndRewritesId() {
        var now = 1_000_000L
        val cache = DnsCache(clock = { now })
        val raw = DnsWire.buildQuery("example.com", DnsType.A, id = 1)
        val resp = TestPackets.response(raw, listOf(RR(DnsType.A, 100, byteArrayOf(1, 2, 3, 4))))
        assertEquals(100, cache.put(DnsWire.parseQuery(raw)!!, resp))
        now += 40_000
        val raw2 = DnsWire.buildQuery("EXAMPLE.com", DnsType.A, id = 555)
        val hit = cache.get(DnsWire.parseQuery(raw2)!!)
        assertNotNull(hit)
        assertEquals(555, DnsWire.id(hit!!))
        assertEquals(60, DnsWire.cacheTtl(hit))
        now += 61_000
        assertNull(cache.get(DnsWire.parseQuery(raw2)!!))
    }

    @Test fun cacheIsBounded() {
        val cache = DnsCache(maxEntries = 10)
        repeat(50) {
            val raw = DnsWire.buildQuery("h$it.example", DnsType.A)
            cache.put(DnsWire.parseQuery(raw)!!, TestPackets.response(raw, listOf(RR(1, 300, byteArrayOf(1, 1, 1, 1)))))
        }
        assertEquals(10, cache.size)
    }
}
