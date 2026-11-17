package com.rocksguard.core

import com.rocksguard.core.TestPackets.CLIENT_V4
import com.rocksguard.core.TestPackets.DNS_V4
import com.rocksguard.core.TestPackets.RR
import com.rocksguard.core.dns.BlockResponse
import com.rocksguard.core.dns.DnsCache
import com.rocksguard.core.dns.DnsType
import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.engine.AppRule
import com.rocksguard.core.engine.EngineConfig
import com.rocksguard.core.engine.EngineListener
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.engine.GuardEngine
import com.rocksguard.core.engine.NetworkKind
import com.rocksguard.core.engine.QueryEvent
import com.rocksguard.core.engine.RuleFirewall
import com.rocksguard.core.engine.Upstream
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.FilterCompiler
import com.rocksguard.core.filter.SourceInfo
import com.rocksguard.core.filter.Verdict
import com.rocksguard.core.net.IpPacket
import com.rocksguard.core.upstream.UpstreamAnswer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class GuardEngineTest {
    private val out = ArrayList<ByteArray>()
    private val events = ArrayList<QueryEvent>()
    private var upstreamCalls = 0
    private var upstreamResponder: (ByteArray) -> ByteArray = { q ->
        TestPackets.response(q, listOf(RR(DnsType.A, 300, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))))
    }

    private val filters = FilterCompiler().apply {
        addList(SourceInfo("t", "Trackers", Category.TRACKERS), StringReader("||tracker.example^\n"))
        addList(SourceInfo("a", "Ads", Category.ADS), StringReader("0.0.0.0 ads.example\n"))
    }.build().withSnapshot(emptyList(), emptyList(), Category.entries.toSet())

    private fun engine(config: EngineConfig = EngineConfig(listOf(DNS_V4)), uid: Int = 10123, firewall: RuleFirewall? = null) = GuardEngine(
        config = config,
        filters = filters,
        upstream = Upstream { q ->
            upstreamCalls++
            UpstreamAnswer(upstreamResponder(q), "Test · HTTPS", true, 12)
        },
        cache = DnsCache(),
        executor = { it.run() },
        output = { out += it },
        listener = object : EngineListener { override fun onQuery(event: QueryEvent) { events += event } },
        uidResolver = { _, _, _, _, _ -> uid },
    ).also { if (firewall != null) it.firewall = firewall }

    private fun send(e: GuardEngine, name: String, type: Int = DnsType.A, id: Int = 0x4242) {
        val pkt = TestPackets.ipv4Udp(CLIENT_V4, DNS_V4, 50000, 53, DnsWire.buildQuery(name, type, id))
        e.handlePacket(pkt, pkt.size)
    }

    private fun lastDns(): ByteArray {
        val p = IpPacket.parse(out.last(), out.last().size)!!
        assertArrayEquals(DNS_V4, p.sourceAddress)
        assertEquals(50000, p.destinationPort)
        return p.udpPayload()
    }

    @Test fun blockedNameIsAnsweredLocallyWithoutUpstream() {
        val e = engine()
        send(e, "cdn.tracker.example")
        assertEquals(0, upstreamCalls)
        assertArrayEquals(ByteArray(4), DnsWire.answerAddresses(lastDns()).single())
        val v = events.single().verdict as Verdict.BlockedByList
        assertEquals(Category.TRACKERS, v.category)
        assertEquals(10123, events.single().uid)
    }

    @Test fun allowedNameIsForwardedThenCached() {
        val e = engine()
        send(e, "example.com", id = 1)
        assertEquals(1, upstreamCalls)
        assertEquals(1, DnsWire.id(lastDns()))
        send(e, "example.com", id = 2)
        assertEquals(1, upstreamCalls)
        assertEquals(2, DnsWire.id(lastDns()))
        assertTrue(events.last().cached)
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), DnsWire.answerAddresses(lastDns()).single())
    }

    @Test fun cnameCloakedTrackerIsBlocked() {
        upstreamResponder = { q ->
            TestPackets.response(q, listOf(
                RR(DnsType.CNAME, 300, TestPackets.name("edge.tracker.example")),
                RR(DnsType.A, 300, byteArrayOf(5, 6, 7, 8), namePointer = q.size + 12),
            ))
        }
        val e = engine()
        send(e, "metrics.shop.example")
        val v = events.single().verdict as Verdict.BlockedCname
        assertEquals("edge.tracker.example", v.cname)
        assertArrayEquals(ByteArray(4), DnsWire.answerAddresses(lastDns()).single())
        // the cached copy is also uncloaked
        send(e, "metrics.shop.example")
        assertTrue(events.last().verdict is Verdict.BlockedCname)
        assertEquals(1, upstreamCalls)
    }

    @Test fun cnameUncloakingCanBeDisabled() {
        upstreamResponder = { q -> TestPackets.response(q, listOf(RR(DnsType.CNAME, 300, TestPackets.name("edge.tracker.example")))) }
        val e = engine(EngineConfig(listOf(DNS_V4), cnameUncloaking = false))
        send(e, "metrics.shop.example")
        assertTrue(events.single().verdict is Verdict.Allowed)
    }

    @Test fun upstreamFailureGivesServfail() {
        upstreamResponder = { throw IOException("down") }
        val e = engine()
        send(e, "example.com")
        assertEquals(DnsWire.RCODE_SERVFAIL, DnsWire.rcode(lastDns()))
        assertTrue(events.single().failed)
    }

    @Test fun firefoxCanaryGetsNxdomain() {
        val e = engine()
        send(e, GuardEngine.CANARY)
        assertEquals(DnsWire.RCODE_NXDOMAIN, DnsWire.rcode(lastDns()))
        assertEquals(0, upstreamCalls)
    }

    @Test fun firewallBlocksAppAtDnsLevelOnMatchingNetwork() {
        var net = NetworkKind.WIFI
        val fw = RuleFirewall(listOf(AppRule("com.social", 10123, blockOnWifi = true)), { net })
        val e = engine(firewall = fw)
        send(e, "example.com")
        assertTrue(events.last().verdict is Verdict.BlockedByFirewall)
        net = NetworkKind.MOBILE
        send(e, "example.com")
        assertTrue(events.last().verdict is Verdict.Allowed)
    }

    @Test fun nxdomainModeHonoured() {
        val e = engine(EngineConfig(listOf(DNS_V4), blockResponse = BlockResponse.NXDOMAIN))
        send(e, "ads.example")
        assertEquals(DnsWire.RCODE_NXDOMAIN, DnsWire.rcode(lastDns()))
    }

    @Test fun ignoresTrafficNotForVirtualDns() {
        val e = engine()
        val pkt = TestPackets.ipv4Udp(CLIENT_V4, byteArrayOf(8, 8, 8, 8), 50000, 53, DnsWire.buildQuery("ads.example", 1))
        e.handlePacket(pkt, pkt.size)
        assertTrue(out.isEmpty())
    }

    @Test fun sealedModeRefusesTcpAndDns() {
        var refused = 0
        val e = GuardEngine(
            EngineConfig(listOf(DNS_V4), gateMode = GateMode.SEALED), filters,
            { error("no upstream in sealed mode") }, DnsCache(), { it.run() }, { out += it },
            object : EngineListener {
                override fun onQuery(event: QueryEvent) { events += event }
                override fun onConnectionRefused(uid: Int) { refused++ }
            },
            { _, _, _, _, _ -> 10500 },
        )
        val syn = TestPackets.ipv4Tcp(CLIENT_V4, byteArrayOf(1, 2, 3, 4), 1234, 443, 10, 0, IpPacket.TCP_SYN)
        e.handlePacket(syn, syn.size)
        assertEquals(1, refused)
        val rst = IpPacket.parse(out.last(), out.last().size)!!
        assertTrue(rst.tcpFlags and IpPacket.TCP_RST != 0)
        val dns = TestPackets.ipv4Udp(CLIENT_V4, byteArrayOf(9, 9, 9, 9), 5000, 53, DnsWire.buildQuery("example.com", 1))
        e.handlePacket(dns, dns.size)
        assertTrue(events.last().verdict is Verdict.BlockedByFirewall)
        assertEquals(10500, events.last().uid)
    }
}
