package com.rocksguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rocksguard.app.RocksGuardApp
import com.rocksguard.app.ui.theme.RocksGuardTheme
import com.rocksguard.app.vpn.GuardController
import com.rocksguard.app.vpn.Phase

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RAISE = "com.rocksguard.extra.RAISE"
    }

    private val graph get() = (application as RocksGuardApp).graph

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) GuardController.start(this)
        else GuardController.set { it.copy(phase = Phase.ERROR, error = "VPN permission was declined, so the guard could not be raised.") }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** One-tap protection: raise or stand down, asking for consent only when needed. */
    fun toggleProtection() {
        val st = GuardController.state.value.phase
        if (st == Phase.RUNNING || st == Phase.STARTING) {
            GuardController.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !graph.settings.value.onboarded
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        graph.settings.update { it.copy(onboarded = true) }
        val intent = GuardController.consentIntent(this)
        if (intent != null) consent.launch(intent) else GuardController.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle()
            LaunchedEffect(settings.secureScreen) {
                if (settings.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            RocksGuardTheme(settings) {
                val dark = com.rocksguard.app.ui.theme.Rg.colors.isDark
                LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                }
                AppRoot { dest, navigate -> Route(dest, graph, navigate, ::toggleProtection) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RAISE, false) == true && !GuardController.state.value.running) {
            intent.removeExtra(EXTRA_RAISE)
            toggleProtection()
        }
    }
}
