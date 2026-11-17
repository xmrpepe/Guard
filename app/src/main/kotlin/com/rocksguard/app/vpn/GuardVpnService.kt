package com.rocksguard.app.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.rocksguard.app.RocksGuardApp
import com.rocksguard.app.data.Settings
import com.rocksguard.app.ui.MainActivity
import com.rocksguard.core.dns.DnsCache
import com.rocksguard.core.engine.AppRule
import com.rocksguard.core.engine.EngineConfig
import com.rocksguard.core.engine.EngineListener
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.engine.GuardEngine
import com.rocksguard.core.engine.NetworkKind
import com.rocksguard.core.engine.QueryEvent
import com.rocksguard.core.engine.RuleFirewall
import com.rocksguard.core.engine.TunnelPlan
import com.rocksguard.core.engine.TunnelPlanner
import com.rocksguard.core.engine.UidResolver
import com.rocksguard.core.engine.Upstream
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.Verdict
import com.rocksguard.core.upstream.DnsProvider
import com.rocksguard.core.upstream.DohTransport
import com.rocksguard.core.upstream.ResolverPool
import com.rocksguard.core.upstream.SocketProtector
import com.rocksguard.core.upstream.UpstreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * RocksGuard's on-device VPN. Nothing leaves the phone through it: the tunnel only
 * carries DNS to a virtual resolver (Watch Gate) or the traffic of sealed apps, which is
 * refused (Sealed Gate). Allowed lookups go to the chosen encrypted resolver directly.
 */
class GuardVpnService : VpnService(), SocketProtector {

    companion object {
        const val ACTION_START = "com.rocksguard.action.START"
        const val ACTION_STOP = "com.rocksguard.action.STOP"
        private const val TAG = "RocksGuard/VPN"
        private const val MTU = 1500
        private const val V6_PREFIX = "fd52:4f43:4b53"
        private val V4_CANDIDATES = listOf("10.111.222", "172.31.222", "192.168.222")

        @Volatile
        var instance: GuardVpnService? = null
            private set
    }

    /** One established tunnel interface with its reader thread. */
    private class Session(val pfd: ParcelFileDescriptor, val plan: TunnelPlan, val dns: List<ByteArray>) {
        val out = FileOutputStream(pfd.fileDescriptor)
        @Volatile var thread: Thread? = null
        fun close() {
            runCatching { pfd.close() }
            thread?.interrupt()
        }
    }

    private val graph get() = (application as RocksGuardApp).graph
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = AtomicReference<Session?>(null)
    private val pool = AtomicReference<ResolverPool?>(null)
    private lateinit var engine: GuardEngine
    private lateinit var executor: ThreadPoolExecutor
    private val cache = DnsCache()
    private lateinit var baseClient: OkHttpClient
    @Volatile private var network = NetworkKind.NONE
    @Volatile private var lastNotify = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    // ---- Lifecycle -----------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(userInitiated = true)
            return START_NOT_STICKY
        }
        // ACTION_START, a sticky restart (null intent) or an always-on start by the system.
        goForeground("Raising the guard…", "Starting protection")
        if (instance == null) {
            instance = this
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            graph.settings.update { it.copy(wantsProtection = true) }
            scope.launch { startProtection() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked consent in system settings.
        Log.i(TAG, "VPN revoked")
        shutdown(userInitiated = true, error = "Another VPN took over, so RocksGuard stood down.")
    }

    override fun onDestroy() {
        shutdown(userInitiated = false)
        super.onDestroy()
    }

    private fun goForeground(title: String, text: String) {
        val n = GuardNotifications.guard(this, title, text, null)
        ServiceCompat.startForeground(
            this, GuardNotifications.ID_GUARD, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun startProtection() {
        if (prepare(this) != null) {
            shutdown(userInitiated = true, error = "RocksGuard needs VPN permission. Tap the shield to grant it.")
            return
        }
        try {
            val s = graph.settings.value
            executor = ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue(1024)).apply {
                allowCoreThreadTimeOut(true) // no idle threads when the phone is quiet
            }
            baseClient = DohTransport.baseClient(this)
            pool.set(buildPool(s))
            engine = GuardEngine(
                config = engineConfig(s, GateMode.WATCH, emptyList()),
                filters = graph.filters.snapshot.value,
                upstream = Upstream { q -> (pool.get() ?: throw IOException("no resolver")).resolve(q) },
                cache = cache,
                executor = executor,
                output = ::writePacket,
                listener = listener,
                uidResolver = uidResolver(),
                firewall = RuleFirewall(graph.rules.resolved()) { network },
            )
            watchNetwork()
            establish()
            observeChanges()
            GuardController.set {
                it.copy(phase = Phase.RUNNING, startedAt = System.currentTimeMillis(), error = null)
            }
            publishHealth()
            updateNotification(force = true)
            graph.filters.ensureDownloaded()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            shutdown(userInitiated = true, error = "The guard could not be raised: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Stops everything. When [userInitiated], protection stays off after a restart. */
    @Synchronized
    fun shutdown(userInitiated: Boolean, error: String? = null) {
        val wasActive = instance === this || session.get() != null
        if (instance === this) instance = null
        if (userInitiated) graph.settings.update { it.copy(wantsProtection = false) }
        if (wasActive) {
            scope.cancel()
            netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            netCallback = null
            session.getAndSet(null)?.close()
            pool.getAndSet(null)?.close()
            if (::executor.isInitialized) executor.shutdownNow()
            cache.clear()
            graph.stats.persist()
        }
        // A late onDestroy must not wipe an error the user still needs to read.
        if (wasActive || error != null) {
            GuardController.set { GuardState(phase = if (error != null) Phase.ERROR else Phase.STOPPED, error = error) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Tunnel ------------------------------------------------------------------------

    private fun pickV4Prefix(): String {
        val used = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().map { it.hostAddress?.substringBeforeLast('.') }
        }.getOrDefault(emptyList())
        return V4_CANDIDATES.firstOrNull { it !in used } ?: V4_CANDIDATES.first()
    }

    /** Builds (or rebuilds) the tunnel from the current settings, rules and network. */
    @Synchronized
    private fun establish() {
        val s = graph.settings.value
        val rules = graph.rules.resolved()
        var plan = TunnelPlanner.plan(s.gateMode, rules, network, packageName)
        var pfd = build(plan)
        if (pfd == null && plan.mode == GateMode.SEALED) {
            // None of the sealed apps could be applied (uninstalled?): never route everyone.
            plan = TunnelPlanner.plan(GateMode.WATCH, rules, network, packageName)
                .copy(notice = "The sealed apps are no longer installed, so the Watch Gate is guarding.")
            pfd = build(plan)
        }
        pfd ?: throw IllegalStateException("Android refused to create the VPN interface")
        val prefix = currentPrefix
        val dns = listOf(InetAddress.getByName("$prefix.1").address, InetAddress.getByName("$V6_PREFIX::1").address)
        engine.config = engineConfig(s, plan.mode, dns)
        engine.firewall = RuleFirewall(rules) { network }
        val next = Session(pfd, plan, dns)
        startReader(next)
        session.getAndSet(next)?.close() // replace the old interface only once the new one is up
        GuardController.set {
            it.copy(
                mode = plan.mode,
                notice = plan.notice,
                sealedApps = plan.allowedPackages.size,
                restrictedApps = rules.count { r -> r.hasBlock && !r.bypass },
                alwaysOn = if (Build.VERSION.SDK_INT >= 29) isAlwaysOn else null,
                lockdown = if (Build.VERSION.SDK_INT >= 29) isLockdownEnabled else null,
            )
        }
        Log.i(TAG, "tunnel up: ${plan.mode}, sealed=${plan.allowedPackages.size}, bypass=${plan.disallowedPackages.size - 1}")
    }

    private var currentPrefix = V4_CANDIDATES.first()

    private fun build(plan: TunnelPlan): ParcelFileDescriptor? {
        val prefix = pickV4Prefix().also { currentPrefix = it }
        val b = Builder()
            .setSession(getString(com.rocksguard.app.R.string.vpn_session))
            .setMtu(MTU)
            .setBlocking(true)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAddress("$prefix.2", 24)
            .addDnsServer("$prefix.1")
            .addRoute("$prefix.1", 32)
            .addAddress("$V6_PREFIX::2", 64)
            .addDnsServer("$V6_PREFIX::1")
            .addRoute("$V6_PREFIX::1", 128)
        if (plan.routeAllTraffic) {
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)
        }
        if (plan.mode == GateMode.SEALED) {
            var applied = 0
            for (p in plan.allowedPackages) {
                try {
                    b.addAllowedApplication(p); applied++
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            if (applied == 0) return null
        } else {
            for (p in plan.disallowedPackages) {
                try {
                    b.addDisallowedApplication(p)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }
        // Without this Android 10+ marks every network as metered while the VPN is up.
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setUnderlyingNetworks(null) // follow the system default network
        return b.establish()
    }

    private fun startReader(s: Session) {
        val t = Thread({
            val input = FileInputStream(s.pfd.fileDescriptor)
            val buf = ByteArray(32 * 1024)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buf) // blocking read: zero CPU while idle
                    if (n > 0) engine.handlePacket(buf, n) else if (n < 0) break
                }
            } catch (e: IOException) {
                // Interface closed (stop or rebuild).
            } catch (e: Exception) {
                Log.e(TAG, "tunnel reader failed", e)
            }
        }, "rg-tunnel")
        t.isDaemon = true
        s.thread = t
        t.start()
    }

    private fun writePacket(bytes: ByteArray) {
        val s = session.get() ?: return
        try {
            synchronized(s) { s.out.write(bytes) }
        } catch (_: IOException) {
            // The interface is being replaced; the client will retry.
        }
    }

    // ---- Configuration -----------------------------------------------------------------

    private fun engineConfig(s: Settings, mode: GateMode, dns: List<ByteArray>) = EngineConfig(
        dnsAddresses = dns,
        gateMode = mode,
        blockResponse = s.blockResponse,
        cnameUncloaking = s.cnameUncloaking,
        blockBypassCanary = s.bypassCanary,
        identifyApps = s.identifyApps,
        mtu = MTU,
    )

    private fun buildPool(s: Settings): ResolverPool {
        val primary = DnsProvider.byId(s.providerId) ?: DnsProvider.CATALOGUE.first()
        val fallback = s.fallbackId?.let { DnsProvider.byId(it) }
        return ResolverPool.build(UpstreamConfig(primary, fallback, s.protocol, s.plaintextFallback), this, baseClient)
    }

    private fun observeChanges() {
        val settings = graph.settings.flow
        // Rule snapshot (lists, user rules, categories): swapped atomically.
        scope.launch { graph.filters.snapshot.collect { engine.filters = it } }
        // Engine behaviour.
        scope.launch {
            settings.map { listOf(it.blockResponse, it.cnameUncloaking, it.bypassCanary, it.identifyApps) }
                .distinctUntilChanged().drop(1).collect {
                    val cur = session.get() ?: return@collect
                    engine.config = engineConfig(graph.settings.value, cur.plan.mode, cur.dns)
                }
        }
        // Resolver choice.
        scope.launch {
            settings.map { listOf(it.providerId, it.fallbackId, it.protocol, it.plaintextFallback) }
                .distinctUntilChanged().drop(1).collect {
                    pool.getAndSet(buildPool(graph.settings.value))?.close()
                    cache.clear()
                    publishHealth()
                }
        }
        // Gate mode or app rules: rebuild the tunnel only when the plan really changes.
        scope.launch {
            settings.map { it.gateMode }.distinctUntilChanged().drop(1).collect { rebuildIfNeeded() }
        }
        scope.launch {
            graph.rules.rules.drop(1).collect {
                engine.firewall = RuleFirewall(graph.rules.resolved()) { network }
                rebuildIfNeeded()
            }
        }
        scope.launch {
            settings.map { it.liveNotification }.distinctUntilChanged().collect { updateNotification(force = true) }
        }
    }

    private fun rebuildIfNeeded() {
        val cur = session.get() ?: return
        val plan = TunnelPlanner.plan(graph.settings.value.gateMode, graph.rules.resolved(), network, packageName)
        if (plan.mode != cur.plan.mode || plan.allowedPackages != cur.plan.allowedPackages ||
            plan.disallowedPackages != cur.plan.disallowedPackages
        ) {
            try {
                establish()
            } catch (e: Exception) {
                Log.e(TAG, "rebuild failed", e)
                GuardController.set { it.copy(notice = "Could not apply the new gate: ${e.message}") }
            }
        } else {
            GuardController.set { it.copy(restrictedApps = graph.rules.resolved().count { r -> r.hasBlock && !r.bypass }) }
        }
    }

    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
                    else -> NetworkKind.OTHER
                }
                if (kind != network) {
                    network = kind
                    GuardController.set { it.copy(network = kind) }
                    // Connections opened on the previous network are dead; drop them.
                    if (::baseClient.isInitialized) baseClient.connectionPool.evictAll()
                    rebuildIfNeeded()
                }
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                val strict = if (Build.VERSION.SDK_INT >= 28) lp.privateDnsServerName else null
                GuardController.set { it.copy(privateDnsStrict = strict) }
            }

            override fun onLost(n: Network) {
                network = NetworkKind.NONE
                GuardController.set { it.copy(network = NetworkKind.NONE) }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
        // Seed the current network synchronously so the first plan is correct.
        cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { cb.onCapabilitiesChanged(n, it) } }
    }

    private fun uidResolver(): UidResolver? {
        if (Build.VERSION.SDK_INT < 29) return null
        return UidResolver { proto, src, sp, dst, dp ->
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(src), sp),
                InetSocketAddress(InetAddress.getByAddress(dst), dp),
            )
        }
    }

    // ---- Events ------------------------------------------------------------------------

    private val listener = object : EngineListener {
        override fun onQuery(event: QueryEvent) {
            val g = graph
            if (g.settings.value.recordActivity) g.activity.add(event)
            g.stats.record(event)
            val v = event.verdict
            if (v is Verdict.BlockedByList && v.category == Category.THREATS && g.settings.value.threatAlerts) {
                GuardNotifications.threat(
                    this@GuardVpnService, event.name, v.source?.name,
                    if (event.uid >= 0) g.apps.labelForUid(event.uid) else null,
                )
            }
            if (event.failed || !event.cached) publishHealthThrottled()
            updateNotification(force = false)
        }

        override fun onConnectionRefused(uid: Int) = graph.stats.recordRefused()
    }

    @Volatile private var lastHealth = 0L

    private fun publishHealthThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastHealth < 2_000) return
        lastHealth = now
        publishHealth()
    }

    private fun publishHealth() {
        val p = pool.get() ?: return
        val total = cache.hits + cache.misses
        GuardController.set {
            it.copy(
                upstream = p.health(),
                cacheEntries = cache.size,
                cacheHitRate = if (total == 0L) 0.0 else cache.hits.toDouble() / total,
            )
        }
    }

    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 15_000) return
        lastNotify = now
        val st = GuardController.state.value
        val live = graph.settings.value.liveNotification
        val title = if (st.mode == GateMode.SEALED) "The Gate Is Sealed" else "The Guard Stands"
        val text = when {
            st.mode == GateMode.SEALED -> "${st.sealedApps} app(s) cut off from the network"
            else -> "Protection active" + (st.activeUpstream?.label?.let { " · $it" } ?: "")
        }
        val n = GuardNotifications.guard(this, title, text, if (live) graph.stats.flow.value.today.blocked else null)
        getSystemService(NotificationManager::class.java).notify(GuardNotifications.ID_GUARD, n)
    }

    // ---- SocketProtector ---------------------------------------------------------------
    override fun protect(socket: DatagramSocket): Boolean = super.protect(socket)
}
