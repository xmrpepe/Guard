package com.rocksguard.app

import android.app.Application
import com.rocksguard.app.data.ActivityLog
import com.rocksguard.app.data.AppRegistry
import com.rocksguard.app.data.AppRulesStore
import com.rocksguard.app.data.FilterRepository
import com.rocksguard.app.data.SettingsStore
import com.rocksguard.app.data.StatsStore
import com.rocksguard.app.system.ListUpdateJob
import com.rocksguard.app.vpn.GuardNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. Deliberately tiny: no DI framework, no reflection. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val filters = FilterRepository(app, settings, scope)
    val activity = ActivityLog()
    val stats = StatsStore(app, scope)
    val apps = AppRegistry(app)
    val rules = AppRulesStore(app, apps)
}

class RocksGuardApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        GuardNotifications.createChannels(this)
        graph.filters.start()
        ListUpdateJob.schedule(this, graph.settings.value)
    }
}
