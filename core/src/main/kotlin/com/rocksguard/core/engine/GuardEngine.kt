package com.rocksguard.core.engine

import com.rocksguard.core.dns.BlockResponse
import com.rocksguard.core.dns.DnsCache
import com.rocksguard.core.dns.DnsFormatException
import com.rocksguard.core.dns.DnsQuery
import com.rocksguard.core.dns.DnsWire
import com.rocksguard.core.filter.FilterSnapshot
import com.rocksguard.core.filter.Verdict
import com.rocksguard.core.net.IpPacket
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/** How the tunnel is being used. See [TunnelPlanner]. */
enum class GateMode {
    /** DNS filtering for every app; per-app firewall enforced at the DNS level. */
    WATCH,

    /** Only firewalled apps are routed into the tunnel and all of their traffic is refused. */
    SEALED,
}

data class EngineConfig(
    /** Addresses of RocksGuard's virtual DNS server inside the tunnel. */
    val dnsAddresses: List<ByteArray>,
    val gateMode: GateMode = GateMode.WATCH,
    val blockResponse: BlockResponse = BlockResponse.NULL_IP,
    val cnameUncloaking: Boolean = true,
    /** Answer `use-application-dns.net` with NXDOMAIN so browsers keep using filtered DNS. */
    val blockBypassCanary: Boolean = true,
    val identifyApps: Boolean = true,
    val mtu: Int = 1500,
)

/** Per-app network rules evaluated on the packet path. Implementations must be fast. */
interface FirewallPolicy {
    /** True when any rule needs to know the uid of a query. */
    val needsUid: Boolean

    fun isBlocked(uid: Int): Boolean

    object AllowAll : FirewallPolicy {
        override val needsUid = false
        override fun isBlocked(uid: Int) = false
    }
}

/** Maps a tunnel flow to the Linux uid of the app that opened it, or -1 when unknown. */
fun interface UidResolver {
    fun uidOf(protocol: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Int
}

/** Upstream resolution, abstracted for tests. */
fun interface Upstream {
    fun resolve(query: ByteArray): com.rocksguard.core.upstream.UpstreamAnswer
}

/** One DNS query as observed by RocksGuard. These feed The Lookout and the counters. */
class QueryEvent(
    val timeMillis: Long,
    val name: String,
    val type: Int,
    val uid: Int,
    val verdict: Verdict,
    val transport: String?,
    val encrypted: Boolean,
    val latencyMs: Long,
    val cached: Boolean,
    val failed: Boolean,
)

interface EngineListener {
    fun onQuery(event: QueryEvent)

    /** A non-DNS connection attempt was refused (Sealed Gate). */
    fun onConnectionRefused(uid: Int) {}
}

/**
 * The heart of RocksGuard. Takes raw IP packets read from the tunnel and writes answers
 * back through [output]. Thread-safe: filters and configuration can be swapped while
 * running without locks on the hot path.
 */
class GuardEngine(
    config: EngineConfig,
    filters: FilterSnapshot,
    private val upstream: Upstream,
    private val cache: DnsCache,
    private val executor: Executor,
    private val output: (ByteArray) -> Unit,
    private val listener: EngineListener,
    private val uidResolver: UidResolver? = null,
    firewall: FirewallPolicy = FirewallPolicy.AllowAll,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val configRef = AtomicReference(config)
    private val filtersRef = AtomicReference(filters)
    private val firewallRef = AtomicReference(firewall)

    var config: EngineConfig
        get() = configRef.get()
        set(v) = configRef.set(v)
    var filters: FilterSnapshot
        get() = filtersRef.get()
        set(v) = filtersRef.set(v)
    var firewall: FirewallPolicy
        get() = firewallRef.get()
        set(v) = firewallRef.set(v)

    /** Entry point for one packet. [buf] may be reused by the caller after this returns. */
    fun handlePacket(buf: ByteArray, len: Int) {
        val packet = IpPacket.parse(buf.copyOf(len), len) ?: return
        val cfg = config
        if (cfg.gateMode == GateMode.SEALED) {
            handleSealed(packet, cfg)
            return
        }
        val toDns = cfg.dnsAddresses.any { it.contentEquals(packet.destinationAddress) }
        if (!toDns) return // nothing else is routed to us in Watch mode
        when {
            packet.isUdp && packet.destinationPort == 53 -> dispatch(packet, cfg)
            // DNS over TCP to the virtual server is refused at once so clients retry over UDP.
            packet.isTcp -> IpPacket.buildTcpReset(packet)?.let(output)
        }
    }

    private fun handleSealed(packet: IpPacket, cfg: EngineConfig) {
        when {
            packet.isUdp && packet.destinationPort == 53 -> dispatch(packet, cfg)
            packet.isTcp -> {
                // Only report the opening SYN so one refused connection counts once.
                if (packet.tcpFlags and IpPacket.TCP_SYN != 0 && packet.tcpFlags and IpPacket.TCP_ACK == 0) {
                    listener.onConnectionRefused(uidFor(packet, cfg, force = true))
                }
                IpPacket.buildTcpReset(packet)?.let(output)
            }
            // Other UDP/ICMP is dropped: there is no route, so it cannot leave the device.
        }
    }

    private fun dispatch(packet: IpPacket, cfg: EngineConfig) {
        val payload = packet.udpPayload()
        val q = DnsWire.parseQuery(payload) ?: return
        try {
            executor.execute { answer(packet, payload, q, cfg) }
        } catch (_: RejectedExecutionException) {
            // Overloaded: fail fast rather than queueing without bound.
            reply(packet, DnsWire.buildErrorResponse(payload, q, DnsWire.RCODE_SERVFAIL), q, cfg)
        }
    }

    private fun uidFor(packet: IpPacket, cfg: EngineConfig, force: Boolean = false): Int {
        val r = uidResolver ?: return -1
        if (!force && !cfg.identifyApps && !firewall.needsUid) return -1
        return try {
            r.uidOf(packet.protocol, packet.sourceAddress, packet.sourcePort, packet.destinationAddress, packet.destinationPort)
        } catch (_: RuntimeException) {
            -1
        }
    }

    private fun answer(packet: IpPacket, payload: ByteArray, q: DnsQuery, cfg: EngineConfig) {
        val start = clock()
        val uid = uidFor(packet, cfg, force = cfg.gateMode == GateMode.SEALED)
        val snapshot = filters

        val preVerdict: Verdict = when {
            cfg.gateMode == GateMode.SEALED -> Verdict.BlockedByFirewall(uid)
            uid >= 0 && firewall.isBlocked(uid) -> Verdict.BlockedByFirewall(uid)
            cfg.blockBypassCanary && q.name == CANARY -> Verdict.BlockedCanary("Keeps browsers on RocksGuard's filtered DNS")
            else -> snapshot.decide(q.name)
        }
        if (preVerdict.blocked) {
            val mode = if (preVerdict is Verdict.BlockedCanary) BlockResponse.NXDOMAIN else cfg.blockResponse
            reply(packet, DnsWire.buildBlockedResponse(payload, q, mode), q, cfg)
            emit(q, uid, preVerdict, null, false, clock() - start, cached = false, failed = false)
            return
        }

        cache.get(q)?.let { cached ->
            val cnameVerdict = if (cfg.cnameUncloaking) uncloak(cached, snapshot) else null
            if (cnameVerdict != null) {
                reply(packet, DnsWire.buildBlockedResponse(payload, q, cfg.blockResponse), q, cfg)
                emit(q, uid, cnameVerdict, null, false, clock() - start, cached = true, failed = false)
            } else {
                reply(packet, cached, q, cfg)
                emit(q, uid, preVerdict, null, false, clock() - start, cached = true, failed = false)
            }
            return
        }

        val ans = try {
            upstream.resolve(payload)
        } catch (e: Exception) {
            reply(packet, DnsWire.buildErrorResponse(payload, q, DnsWire.RCODE_SERVFAIL), q, cfg)
            emit(q, uid, preVerdict, null, false, clock() - start, cached = false, failed = true)
            return
        }
        val response = ans.response
        if (response.size < DnsWire.HEADER || DnsWire.id(response) != q.id) {
            reply(packet, DnsWire.buildErrorResponse(payload, q, DnsWire.RCODE_SERVFAIL), q, cfg)
            emit(q, uid, preVerdict, ans.transport, ans.encrypted, clock() - start, cached = false, failed = true)
            return
        }
        cache.put(q, response)
        val cnameVerdict = if (cfg.cnameUncloaking) uncloak(response, snapshot) else null
        if (cnameVerdict != null) {
            reply(packet, DnsWire.buildBlockedResponse(payload, q, cfg.blockResponse), q, cfg)
            emit(q, uid, cnameVerdict, ans.transport, ans.encrypted, clock() - start, cached = false, failed = false)
        } else {
            reply(packet, response, q, cfg)
            emit(q, uid, preVerdict, ans.transport, ans.encrypted, clock() - start, cached = false, failed = false)
        }
    }

    /**
     * CNAME uncloaking: trackers hide behind first-party names (metrics.shop.example →
     * CNAME → tracker.example). Each CNAME target is checked against the same rules.
     */
    private fun uncloak(response: ByteArray, snapshot: FilterSnapshot): Verdict? {
        val cnames = try {
            DnsWire.answerCnames(response)
        } catch (_: DnsFormatException) {
            return null
        }
        for (c in cnames) {
            val v = snapshot.decide(c)
            if (v is Verdict.AllowedByUser) return null // explicit user trust wins
            if (v.blocked) return Verdict.BlockedCname(c, v)
        }
        return null
    }

    private fun reply(packet: IpPacket, response: ByteArray, q: DnsQuery, cfg: EngineConfig) {
        val headroom = if (packet.version == 4) 28 else 48
        val body = if (response.size > cfg.mtu - headroom) DnsWire.truncate(response, q) else response
        output(IpPacket.buildUdpReply(packet, body))
    }

    private fun emit(q: DnsQuery, uid: Int, v: Verdict, transport: String?, enc: Boolean, ms: Long, cached: Boolean, failed: Boolean) {
        listener.onQuery(QueryEvent(clock(), q.name, q.type, uid, v, transport, enc, ms, cached, failed))
    }

    companion object {
        const val CANARY = "use-application-dns.net"
    }
}
