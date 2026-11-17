package com.rocksguard.app.system

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.rocksguard.app.RocksGuardApp
import com.rocksguard.app.data.Settings
import com.rocksguard.app.ui.MainActivity
import com.rocksguard.app.vpn.GuardController
import com.rocksguard.app.vpn.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Restores protection after a reboot or app update if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as RocksGuardApp).graph
        val s = graph.settings.value
        if (!s.startOnBoot || !s.wantsProtection) return
        if (GuardController.consentIntent(context) != null) return // consent was revoked
        try {
            GuardController.start(context)
        } catch (e: Exception) {
            Log.w("RocksGuard/Boot", "could not restore protection", e)
        }
    }
}

/** One-tap protection from Quick Settings. */
class GuardTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { GuardController.state.collectLatest { render(it.phase) } }
    }

    override fun onStopListening() {
        scope?.let { (it.coroutineContext[Job])?.cancel() }
        scope = null
    }

    private fun render(phase: Phase) {
        val tile = qsTile ?: return
        tile.state = when (phase) {
            Phase.RUNNING, Phase.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (phase) {
                Phase.RUNNING -> "The Guard Stands"
                Phase.STARTING -> "Raising…"
                else -> "The Guard Rests"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        val running = GuardController.state.value.phase.let { it == Phase.RUNNING || it == Phase.STARTING }
        if (running) {
            GuardController.stop(this)
            return
        }
        if (GuardController.consentIntent(this) != null) {
            // Consent needs an activity; open the app on the Fortress.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_RAISE, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
            return
        }
        GuardController.start(this)
    }
}

/**
 * Refreshes filter lists about once a day, only when the battery is not low and (by
 * default) on an unmetered network. Uses the platform JobScheduler: no extra library.
 */
class ListUpdateJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as RocksGuardApp).graph
        job = graph.scope.launch {
            val changed = runCatching { graph.filters.updateAll() }.getOrDefault(0)
            Log.i("RocksGuard/Lists", "scheduled update finished, $changed list(s) changed")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true // retry later
    }

    companion object {
        private const val JOB_ID = 4201

        fun schedule(context: Context, s: Settings) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (!s.autoUpdateLists) {
                js.cancel(JOB_ID); return
            }
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ListUpdateJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(if (s.updateOnUnmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            val existing = js.getPendingJob(JOB_ID)
            if (existing == null || existing.networkType != info.networkType) js.schedule(info)
        }
    }
}
