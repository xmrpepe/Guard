package com.rocksguard.app.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import com.rocksguard.core.engine.AppRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val usesInternet: Boolean,
)

/** Installed apps, labels and icons, with memory-bounded caches. */
class AppRegistry(private val context: Context) {
    private val pm = context.packageManager
    private val labels = ConcurrentHashMap<Int, String>()
    private val icons = object : LruCache<String, ImageBitmap>(96) {}
    @Volatile private var cached: List<InstalledApp>? = null

    suspend fun installed(refresh: Boolean = false): List<InstalledApp> = withContext(Dispatchers.IO) {
        cached?.takeIf { !refresh }?.let { return@withContext it }
        val flags = PackageManager.GET_PERMISSIONS
        @Suppress("DEPRECATION")
        val pkgs = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }
        val list = pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            if (p.packageName == context.packageName) return@mapNotNull null
            InstalledApp(
                packageName = p.packageName,
                label = ai.loadLabel(pm).toString(),
                uid = ai.uid,
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                usesInternet = p.requestedPermissions?.contains(Manifest.permission.INTERNET) == true,
            )
        }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        list.forEach { labels.putIfAbsent(it.uid, it.label) }
        cached = list
        list
    }

    fun uidOf(packageName: String): Int? = try {
        pm.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** A human name for a uid seen in the activity log. */
    fun labelForUid(uid: Int): String {
        if (uid < 0) return "Unidentified"
        labels[uid]?.let { return it }
        val label = when {
            uid == Process.SYSTEM_UID || uid == 0 -> "Android system"
            uid == 1051 -> "System DNS resolver"
            uid < Process.FIRST_APPLICATION_UID -> "System service"
            else -> {
                val pkgs = pm.getPackagesForUid(uid)
                when {
                    pkgs.isNullOrEmpty() -> "App $uid"
                    pkgs.size == 1 -> runCatching { pm.getApplicationInfo(pkgs[0], 0).loadLabel(pm).toString() }.getOrDefault(pkgs[0])
                    else -> "Shared: " + pkgs.joinToString(", ")
                }
            }
        }
        labels[uid] = label
        return label
    }

    /** App icon rendered at [sizePx], cached. Call off the main thread. */
    fun icon(packageName: String, sizePx: Int): ImageBitmap? {
        icons.get(packageName)?.let { return it }
        return try {
            val d = pm.getApplicationIcon(packageName)
            val bmp = createBitmap(sizePx, sizePx)
            val c = Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(c)
            bmp.asImageBitmap().also { icons.put(packageName, it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** The Ramparts rules, persisted by package name. */
class AppRulesStore(context: Context, private val registry: AppRegistry) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(read())
    val rules: StateFlow<Map<String, AppRule>> = _rules.asStateFlow()

    private fun read(): Map<String, AppRule> =
        (prefs.getStringSet("rules", emptySet()) ?: emptySet()).mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) return@mapNotNull null
            AppRule(p[0], -1, p[1] == "1", p[2] == "1", p[3] == "1")
        }.associateBy { it.packageName }

    @Synchronized
    fun update(packageName: String, transform: (AppRule) -> AppRule) {
        val current = _rules.value[packageName] ?: AppRule(packageName, -1)
        val next = transform(current)
        val map = _rules.value.toMutableMap()
        if (!next.hasBlock && !next.bypass) map.remove(packageName) else map[packageName] = next
        _rules.value = map
        prefs.edit {
            putStringSet("rules", map.values.map {
                "${it.packageName}|${if (it.blockOnWifi) 1 else 0}|${if (it.blockOnMobile) 1 else 0}|${if (it.bypass) 1 else 0}"
            }.toSet())
        }
    }

    /** Rules with uids resolved; rules for uninstalled apps are skipped. */
    fun resolved(): List<AppRule> = _rules.value.values.mapNotNull { r ->
        registry.uidOf(r.packageName)?.let { r.copy(uid = it) }
    }
}
