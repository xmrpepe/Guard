package com.rocksguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.engine.NetworkKind
import com.rocksguard.core.upstream.TransportHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { STOPPED, STARTING, RUNNING, ERROR }

/** Everything the UI needs to know about the running guard. All values are measured. */
data class GuardState(
    val phase: Phase = Phase.STOPPED,
    val mode: GateMode = GateMode.WATCH,
    val notice: String? = null,
    val error: String? = null,
    val startedAt: Long = 0,
    val network: NetworkKind = NetworkKind.NONE,
    val upstream: List<TransportHealth> = emptyList(),
    val sealedApps: Int = 0,
    val restrictedApps: Int = 0,
    val alwaysOn: Boolean? = null,
    val lockdown: Boolean? = null,
    /** Hostname when Android's Private DNS is in strict mode (bypasses RocksGuard). */
    val privateDnsStrict: String? = null,
    val cacheEntries: Int = 0,
    val cacheHitRate: Double = 0.0,
) {
    val running get() = phase == Phase.RUNNING
    val activeUpstream get() = upstream.firstOrNull { it.available && it.successes > 0 } ?: upstream.firstOrNull()
    val upstreamHealthy get() = upstream.any { it.available }
}

/** Single source of truth for starting and stopping protection. */
object GuardController {
    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    internal fun set(transform: (GuardState) -> GuardState) = _state.update(transform)

    /** Intent the activity must launch to get VPN consent, or null when already granted. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context) {
        _state.update { it.copy(phase = Phase.STARTING, error = null) }
        ContextCompat.startForegroundService(
            context, Intent(context, GuardVpnService::class.java).setAction(GuardVpnService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        val running = GuardVpnService.instance
        if (running != null) running.shutdown(userInitiated = true)
        else _state.update { it.copy(phase = Phase.STOPPED) }
    }
}
