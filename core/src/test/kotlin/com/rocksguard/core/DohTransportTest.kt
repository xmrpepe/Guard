package com.rocksguard.core

import com.rocksguard.core.TestPackets.RR
import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.upstream.DnsTransport
import com.rocksguard.core.upstream.DohTransport
import com.rocksguard.core.upstream.ResolverPool
import com.rocksguard.core.upstream.SocketProtector
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DohTransportTest {
    @Test fun postsWireFormatWithZeroIdAndRestoresId() {
        MockWebServer().use { server ->
            val query = DnsWire.buildQuery("example.com", 1, id = 0x7777)
            val zeroIdQuery = query.copyOf().also { DnsWire.setId(it, 0) }
            val answer = TestPackets.response(zeroIdQuery, listOf(RR(1, 60, byteArrayOf(1, 2, 3, 4))))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message").setBody(Buffer().write(answer)))
            server.start()
            val t = DohTransport(server.url("/dns-query").toString(), DohTransport.baseClient(SocketProtector.None))
            val r = t.exchange(query)
            assertEquals(0x7777, DnsWire.id(r))
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("application/dns-message", req.getHeader("Content-Type"))
            val sent = req.body.readByteArray()
            assertEquals(0, DnsWire.id(sent))
        }
    }

    @Test(expected = IOException::class)
    fun rejectsWrongContentType() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>captive portal</html>"))
            server.start()
            DohTransport(server.url("/dns-query").toString(), DohTransport.baseClient(SocketProtector.None))
                .exchange(DnsWire.buildQuery("example.com", 1))
        }
    }

    @Test fun poolFailsOverAndTracksHealth() {
        var calls = 0
        val bad = object : DnsTransport {
            override val label = "bad"; override val encrypted = true
            override fun exchange(query: ByteArray): ByteArray { calls++; throw IOException("down") }
        }
        val good = object : DnsTransport {
            override val label = "good"; override val encrypted = true
            override fun exchange(query: ByteArray) = TestPackets.response(query, emptyList())
        }
        val pool = ResolverPool(listOf(bad, good))
        repeat(5) { assertEquals("good", pool.resolve(DnsWire.buildQuery("a.example", 1)).transport) }
        // circuit opens after three consecutive failures, so the bad resolver stops being tried first
        assertEquals(3, calls)
        val h = pool.health()
        assertTrue(!h[0].available && h[1].available)
    }
}
