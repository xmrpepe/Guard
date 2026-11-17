package com.rocksguard.core.engine

/** Kind of network the device is on, for per-network firewall rules. */
enum class NetworkKind { WIFI, MOBILE, OTHER, NONE }

/** The Ramparts rule for one app. */
data class AppRule(
    val packageName: String,
    val uid: Int,
    val blockOnWifi: Boolean = false,
    val blockOnMobile: Boolean = false,
    /** Excluded from RocksGuard entirely (for apps that refuse to work behind a VPN). */
    val bypass: Boolean = false,
) {
    fun blockedOn(network: NetworkKind): Boolean = when (network) {
        NetworkKind.WIFI -> blockOnWifi
        NetworkKind.MOBILE -> blockOnMobile
        // Ethernet, VPN-over-something, unknown: block if the app is blocked everywhere.
        NetworkKind.OTHER, NetworkKind.NONE -> blockOnWifi && blockOnMobile
    }

    val hasBlock get() = blockOnWifi || blockOnMobile
}

/** Firewall evaluated per DNS query in Watch mode. */
class RuleFirewall(rules: Collection<AppRule>, private val network: () -> NetworkKind) : FirewallPolicy {
    private val byUid: Map<Int, List<AppRule>> = rules.filter { it.hasBlock && !it.bypass }.groupBy { it.uid }
    override val needsUid = byUid.isNotEmpty()
    override fun isBlocked(uid: Int): Boolean {
        val rules = byUid[uid] ?: return false
        val n = network()
        return rules.any { it.blockedOn(n) }
    }
}

/** Exactly how the VPN interface should be built. */
data class TunnelPlan(
    val mode: GateMode,
    /** SEALED: only these apps are routed into the tunnel (addAllowedApplication). */
    val allowedPackages: List<String>,
    /** WATCH: these apps skip the tunnel (addDisallowedApplication). Always contains RocksGuard. */
    val disallowedPackages: List<String>,
    /** SEALED routes everything; WATCH routes only the virtual DNS server. */
    val routeAllTraffic: Boolean,
    /** Shown to the user when the plan differs from what they asked for. */
    val notice: String? = null,
)

object TunnelPlanner {
    /**
     * Produces a safe plan. Most important invariant: Sealed Gate with an empty app list
     * would route *every* app into a black hole (Android treats "no allowed apps" as
     * "all apps"), so in that case RocksGuard falls back to Watch mode instead.
     */
    fun plan(requested: GateMode, rules: Collection<AppRule>, network: NetworkKind, ownPackage: String): TunnelPlan {
        val bypass = rules.filter { it.bypass && it.packageName != ownPackage }.map { it.packageName }.distinct()
        val watch = TunnelPlan(
            mode = GateMode.WATCH,
            allowedPackages = emptyList(),
            disallowedPackages = (listOf(ownPackage) + bypass).distinct(),
            routeAllTraffic = false,
        )
        if (requested == GateMode.WATCH) return watch
        val sealed = rules
            .filter { !it.bypass && it.packageName != ownPackage && it.blockedOn(network) }
            .map { it.packageName }
            .distinct()
        if (sealed.isEmpty()) {
            return watch.copy(notice = "No apps are sealed on this network, so the Watch Gate is guarding instead.")
        }
        return TunnelPlan(
            mode = GateMode.SEALED,
            allowedPackages = sealed,
            disallowedPackages = emptyList(),
            routeAllTraffic = true,
        )
    }
}
