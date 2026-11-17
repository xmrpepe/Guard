package com.rocksguard.core

import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.upstream.DnsProvider
import com.rocksguard.core.upstream.DohTransport
import com.rocksguard.core.upstream.SocketProtector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: `-Drocksguard.live=true`. Queries every catalogue resolver over real DoH. */
class LiveResolverTest {
    @Test fun everyCatalogueResolverAnswers() {
        assumeTrue(System.getProperty("rocksguard.live") == "true")
        val client = DohTransport.baseClient(SocketProtector.None, 10_000)
        for (p in DnsProvider.CATALOGUE) {
            val start = System.nanoTime()
            val r = DohTransport(p.dohUrl, client).exchange(DnsWire.buildQuery("example.com", 1, id = 4321))
            val ms = (System.nanoTime() - start) / 1_000_000
            val addrs = DnsWire.answerAddresses(r)
            println("LIVE ${p.name}: rcode=${DnsWire.rcode(r)} answers=${addrs.size} ${ms}ms")
            assertTrue(p.name, DnsWire.id(r) == 4321 && addrs.isNotEmpty())
        }
    }
}
