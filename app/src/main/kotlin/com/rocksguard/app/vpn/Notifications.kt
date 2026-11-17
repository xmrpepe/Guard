package com.rocksguard.app.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rocksguard.app.R
import com.rocksguard.app.ui.MainActivity
import java.text.NumberFormat

object GuardNotifications {
    const val CHANNEL_GUARD = "guard"
    const val CHANNEL_THREATS = "threats"
    const val ID_GUARD = 1
    private const val ID_THREAT = 2
    private const val SALTIRE = 0xFF0065BD.toInt()
    private const val ALERT = 0xFFC44545.toInt()

    @Volatile private var lastThreatAt = 0L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, context.getString(R.string.channel_guard), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_guard_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_THREATS, context.getString(R.string.channel_threats), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_threats_desc)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun guard(context: Context, title: String, text: String, blockedToday: Long?): Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, GuardVpnService::class.java).setAction(GuardVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = if (blockedToday != null) "$text · ${NumberFormat.getIntegerInstance().format(blockedToday)} blocked today" else text
        return NotificationCompat.Builder(context, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(SALTIRE)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stand down", stop)
            .build()
    }

    /** "Threat Repelled" alert, rate-limited to one every 20 seconds. */
    fun threat(context: Context, domain: String, source: String?, app: String?) {
        val now = System.currentTimeMillis()
        if (now - lastThreatAt < 20_000) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        lastThreatAt = now
        val detail = buildString {
            append("Malicious connection blocked: ").append(domain)
            if (app != null) append("\nRequested by ").append(app)
            if (source != null) append("\nListed in ").append(source)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_THREATS)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setColor(ALERT)
            .setContentTitle("Threat Repelled")
            .setContentText("Malicious connection blocked: $domain")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_THREAT, n)
    }
}
