package com.rocksguard.core.upstream

import com.rocksguard.core.dns.DnsWire
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress

enum class DnsProtocol { DOH, DOT, PLAIN }

/**
 * A public resolver. Every entry below was checked to answer RFC 8484 queries at the
 * listed URL; bootstrap addresses are the providers' published anycast addresses.
 */
data class DnsProvider(
    val id: String,
    val name: String,
    val summary: String,
    val dohUrl: String,
    val dotHost: String?,
    val addresses: List<String>,
    val blocksMalware: Boolean,
    val jurisdiction: String,
) {
    val dohHost: String get() = dohUrl.substringAfter("://").substringBefore('/').substringBefore(':')

    companion object {
        val CATALOGUE = listOf(
            DnsProvider(
                "quad9", "Quad9", "Non-profit resolver that blocks known malicious domains.",
                "https://dns.quad9.net/dns-query", "dns.quad9.net",
                listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"), true, "Switzerland",
            ),
            DnsProvider(
                "cloudflare", "Cloudflare", "Fast global resolver with no filtering.",
                "https://cloudflare-dns.com/dns-query", "one.one.one.one",
                listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"), false, "United States",
            ),
            DnsProvider(
                "cloudflare-security", "Cloudflare Security", "Cloudflare with malware blocking.",
                "https://security.cloudflare-dns.com/dns-query", "security.cloudflare-dns.com",
                listOf("1.1.1.2", "1.0.0.2", "2606:4700:4700::1112", "2606:4700:4700::1002"), true, "United States",
            ),
            DnsProvider(
                "mullvad", "Mullvad", "Privacy-focused resolver with no logging and no filtering.",
                "https://dns.mullvad.net/dns-query", "dns.mullvad.net",
                listOf("194.242.2.2", "2a07:e340::2"), false, "Sweden",
            ),
            DnsProvider(
                "dns4eu", "DNS4EU Protective", "EU-operated resolver that blocks malware and phishing.",
                "https://protective.joindns4.eu/dns-query", "protective.joindns4.eu",
                listOf("86.54.11.1", "86.54.11.201"), true, "European Union",
            ),
            DnsProvider(
                "adguard", "AdGuard DNS", "Resolver with its own ad and tracker blocking.",
                "https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com",
                listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"), false, "Cyprus",
            ),
            DnsProvider(
                "google", "Google Public DNS", "Large, reliable resolver with no filtering.",
                "https://dns.google/dns-query", "dns.google",
                listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"), false, "United States",
            ),
        )

        fun byId(id: String) = CATALOGUE.firstOrNull { it.id == id }
    }
}

/** Upstream configuration chosen in The Watchtower. */
data class UpstreamConfig(
    val primary: DnsProvider,
    val fallback: DnsProvider?,
    val protocol: DnsProtocol,
    /** If every encrypted route fails, fall back to plaintext DNS (off by default). */
    val allowPlaintextFallback: Boolean = false,
)

/** Result of one upstream exchange. */
class UpstreamAnswer(val response: ByteArray, val transport: String, val encrypted: Boolean, val latencyMs: Long)

/** Live health of one transport as shown in The Watchtower. */
data class TransportHealth(
    val label: String,
    val encrypted: Boolean,
    val averageLatencyMs: Long?,
    val failures: Int,
    val successes: Long,
    val available: Boolean,
)

/**
 * Ordered set of transports with failover and a simple circuit breaker: a transport that
 * fails three times in a row is skipped for 30 s, then probed again by real traffic.
 * Latency is tracked as an exponentially-weighted moving average.
 */
class ResolverPool(private val transports: List<DnsTransport>, private val clock: () -> Long = System::currentTimeMillis) {

    private class State {
        var consecutiveFailures = 0
        var openUntil = 0L
        var ewma = -1.0
        var successes = 0L
        var failuresTotal = 0
    }

    private val states = transports.map { State() }

    init {
        require(transports.isNotEmpty()) { "no transports" }
    }

    fun resolve(query: ByteArray): UpstreamAnswer {
        var lastError: IOException? = null
        val now = clock()
        // First pass: healthy transports. Second pass: everything (better than failing).
        val order = transports.indices.sortedBy { if (states[it].openUntil > now) 1 else 0 }
        for (i in order) {
            val t = transports[i]
            val start = System.nanoTime()
            try {
                val resp = t.exchange(query)
                val ms = (System.nanoTime() - start) / 1_000_000
                record(i, success = true, ms)
                return UpstreamAnswer(resp, t.label, t.encrypted, ms)
            } catch (e: IOException) {
                record(i, success = false, 0)
                lastError = e
            } catch (e: RuntimeException) {
                record(i, success = false, 0)
                lastError = IOException(e)
            }
        }
        throw lastError ?: IOException("no upstream")
    }

    @Synchronized
    private fun record(i: Int, success: Boolean, ms: Long) {
        val s = states[i]
        if (success) {
            s.consecutiveFailures = 0
            s.openUntil = 0
            s.successes++
            s.ewma = if (s.ewma < 0) ms.toDouble() else s.ewma * 0.8 + ms * 0.2
        } else {
            s.failuresTotal++
            if (++s.consecutiveFailures >= 3) s.openUntil = clock() + 30_000
        }
    }

    @Synchronized
    fun health(): List<TransportHealth> {
        val now = clock()
        return transports.mapIndexed { i, t ->
            val s = states[i]
            TransportHealth(
                t.label, t.encrypted,
                if (s.ewma < 0) null else s.ewma.toLong(),
                s.failuresTotal, s.successes, s.openUntil <= now,
            )
        }
    }

    fun close() = transports.forEach { runCatching { it.close() } }

    companion object {
        /** Builds the transport chain for a configuration. */
        fun build(config: UpstreamConfig, protector: SocketProtector, baseClient: OkHttpClient): ResolverPool {
            val list = ArrayList<DnsTransport>()
            fun add(p: DnsProvider) {
                val addrs = p.addresses.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                when (config.protocol) {
                    DnsProtocol.DOH -> {
                        val client = baseClient.newBuilder().dns(BootstrapDns(mapOf(p.dohHost to addrs))).build()
                        list += DohTransport(p.dohUrl, client, "${p.name} · HTTPS")
                    }
                    DnsProtocol.DOT -> if (p.dotHost != null) {
                        list += DotTransport(p.dotHost, addrs, protector, label = "${p.name} · TLS")
                    }
                    DnsProtocol.PLAIN -> addrs.firstOrNull()?.let { list += UdpTransport(it, protector, label = "${p.name} · plain") }
                }
            }
            add(config.primary)
            config.fallback?.takeIf { it.id != config.primary.id }?.let(::add)
            if (config.allowPlaintextFallback && config.protocol != DnsProtocol.PLAIN) {
                config.primary.addresses.firstOrNull()?.let { a ->
                    list += UdpTransport(InetAddress.getByName(a), protector, label = "${config.primary.name} · plain")
                }
            }
            return ResolverPool(list)
        }

        /** Measures round-trip time to a provider with a real query (for the resolver test). */
        fun probe(transport: DnsTransport, name: String = "example.com"): Long {
            val q = DnsWire.buildQuery(name, 1, id = (Math.random() * 65535).toInt())
            val start = System.nanoTime()
            val r = transport.exchange(q)
            if (!DnsWire.isResponse(r)) throw IOException("not a response")
            return (System.nanoTime() - start) / 1_000_000
        }
    }
}
