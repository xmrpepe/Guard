package com.rocksguard.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.rocksguard.app.AppGraph
import com.rocksguard.core.filter.Category
import com.rocksguard.core.upstream.DnsProtocol
import com.rocksguard.core.upstream.DnsProvider
import com.rocksguard.core.upstream.DnsTransport
import com.rocksguard.core.upstream.DohTransport
import com.rocksguard.core.upstream.DotTransport
import com.rocksguard.core.upstream.ResolverPool
import com.rocksguard.core.upstream.SocketProtector
import com.rocksguard.core.upstream.BootstrapDns
import com.rocksguard.core.upstream.UdpTransport
import com.rocksguard.app.vpn.GuardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress

enum class CheckStatus { OK, WARN, FAIL, INFO }

data class Check(
    val title: String,
    val detail: String,
    val status: CheckStatus,
    val actionLabel: String? = null,
    val action: Intent? = null,
)

/** Measures real round-trip time to resolvers (used by the Watchtower and inspection). */
object ResolverProbe {
    private val base by lazy { DohTransport.baseClient(SocketProtector.None, 6_000) }

    fun transport(p: DnsProvider, protocol: DnsProtocol): DnsTransport {
        val addrs = p.addresses.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return when (protocol) {
            DnsProtocol.DOH -> DohTransport(p.dohUrl, base.newBuilder().dns(BootstrapDns(mapOf(p.dohHost to addrs))).build())
            DnsProtocol.DOT -> DotTransport(p.dotHost ?: p.dohHost, addrs, SocketProtector.None)
            DnsProtocol.PLAIN -> UdpTransport(addrs.first(), SocketProtector.None)
        }
    }

    /** Milliseconds, or null when unreachable. Two probes; the second reuses the connection. */
    suspend fun measure(p: DnsProvider, protocol: DnsProtocol): Long? = withContext(Dispatchers.IO) {
        val t = transport(p, protocol)
        try {
            runCatching { ResolverPool.probe(t) }
            runCatching { ResolverPool.probe(t) }.getOrNull()
        } finally {
            t.close()
        }
    }
}

/** Runs the Fortress inspection: every check reflects real device state. */
suspend fun inspectFortress(context: Context, graph: AppGraph): List<Check> = coroutineScope {
    val s = graph.settings.value
    val guard = GuardController.state.value
    val provider = DnsProvider.byId(s.providerId) ?: DnsProvider.CATALOGUE.first()
    val latency = async { ResolverProbe.measure(provider, s.protocol) }
    val checks = ArrayList<Check>()

    checks += if (guard.running) {
        Check("The Guard Stands", "Protection is running in ${if (guard.mode.name == "SEALED") "Sealed" else "Watch"} Gate mode.", CheckStatus.OK)
    } else {
        Check("The Guard Is Down", "Protection is not running. Raise the guard from the Fortress.", CheckStatus.FAIL)
    }

    val ms = latency.await()
    checks += when {
        ms == null -> Check("Resolver unreachable", "${provider.name} did not answer a test lookup. The fallback resolver will be used if set.", CheckStatus.FAIL)
        s.protocol == DnsProtocol.PLAIN -> Check("DNS is not encrypted", "${provider.name} answered in $ms ms, but over plain DNS that your network can read.", CheckStatus.WARN)
        else -> Check("DNS encrypted", "${provider.name} answered over ${if (s.protocol == DnsProtocol.DOH) "HTTPS" else "TLS"} in $ms ms.", CheckStatus.OK)
    }
    if (s.plaintextFallback && s.protocol != DnsProtocol.PLAIN) {
        checks += Check("Unencrypted fallback allowed", "If encrypted resolvers fail, lookups may go out in plain text. Turn this off in the Watchtower for strict privacy.", CheckStatus.WARN)
    }

    val compile = graph.filters.compileState.value
    val lists = graph.filters.lists.value.filter { it.enabled }
    checks += if (compile.totalDomains >= 1000) {
        Check("Blocklists loaded", "${"%,d".format(compile.totalDomains)} domains guarded from ${lists.size} lists.", CheckStatus.OK)
    } else {
        Check("Only the built-in lists are loaded", "Download the Armoury's lists for full protection.", CheckStatus.WARN)
    }
    val week = 7L * 24 * 3600 * 1000
    val stale = lists.filter { it.list.url != null && (it.meta.error != null || System.currentTimeMillis() - it.meta.updatedAt > week) }
    if (stale.isNotEmpty()) {
        checks += Check("Some lists are out of date", stale.joinToString { it.list.name } + " — update them in the Armoury.", CheckStatus.WARN)
    }
    val threatList = lists.any { it.list.category == Category.THREATS }
    checks += if (Category.THREATS in s.categories && threatList) {
        Check("Threat protection on", "Known malware and phishing domains are blocked.", CheckStatus.OK)
    } else {
        Check("Threat protection off", "Enable a threat list in the Armoury and Threats in the Shield.", CheckStatus.WARN)
    }

    guard.privateDnsStrict?.let {
        checks += Check(
            "Private DNS bypasses RocksGuard",
            "Android's Private DNS is set to $it, which sends lookups straight there and skips filtering. Set it to Off or Automatic.",
            CheckStatus.WARN, "Open network settings", Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
    }
    if (guard.running && guard.alwaysOn != null) {
        checks += if (guard.alwaysOn == true) {
            Check("Always-on VPN", if (guard.lockdown == true) "On, and connections without the VPN are blocked." else "On. Android restarts the guard if it stops.", CheckStatus.OK)
        } else {
            Check("Always-on VPN is off", "Turn on Always-on VPN for RocksGuard so protection survives restarts and app kills.", CheckStatus.INFO, "Open VPN settings", Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }
    val pm = context.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        checks += Check(
            "Battery optimisation applies",
            "Some manufacturers stop background services aggressively. Exempting RocksGuard keeps the guard standing. It uses no CPU while idle.",
            CheckStatus.INFO, "Battery settings", Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        checks += Check(
            "Notifications are off", "Threat alerts and the live status cannot be shown.", CheckStatus.INFO, "App settings",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
    }
    val order = mapOf(CheckStatus.FAIL to 0, CheckStatus.WARN to 1, CheckStatus.INFO to 2, CheckStatus.OK to 3)
    checks.sortedBy { order.getValue(it.status) }
}

/** Measures every catalogue provider in parallel. */
suspend fun measureAll(protocol: DnsProtocol): Map<String, Long?> = coroutineScope {
    DnsProvider.CATALOGUE.map { p -> async { p.id to ResolverProbe.measure(p, protocol) } }.awaitAll().toMap()
}
