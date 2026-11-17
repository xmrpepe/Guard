package com.rocksguard.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rocksguard.core.brand.ThemeId
import com.rocksguard.core.dns.BlockResponse
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.filter.Category
import com.rocksguard.core.upstream.DnsProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Brightness { SYSTEM, LIGHT, DARK, SCHEDULED }
enum class MotionPref { SYSTEM, REDUCED, FULL }
enum class EffectsPref { AUTO, FULL, LITE }

/** Fortress dashboard widgets, in the order the user arranged them. */
enum class Widget(val title: String) {
    ADVERTS("Adverts Blocked"),
    TRACKERS("Trackers Stopped"),
    THREATS("Threats Prevented"),
    DNS("DNS Encryption"),
    FIREWALL("Firewall Status"),
    ACTIVITY("Network Activity"),
}

data class WidgetSlot(val widget: Widget, val visible: Boolean)

data class Settings(
    // Watchtower
    val providerId: String = "quad9",
    val fallbackId: String? = "cloudflare",
    val protocol: DnsProtocol = DnsProtocol.DOH,
    val plaintextFallback: Boolean = false,
    // Shield
    val categories: Set<Category> = setOf(Category.ADS, Category.TRACKERS, Category.THREATS, Category.BYPASS),
    val blockResponse: BlockResponse = BlockResponse.NULL_IP,
    val cnameUncloaking: Boolean = true,
    val bypassCanary: Boolean = true,
    val userBlocked: Set<String> = emptySet(),
    val userAllowed: Set<String> = emptySet(),
    // Ramparts
    val gateMode: GateMode = GateMode.WATCH,
    val identifyApps: Boolean = true,
    // Behaviour
    val startOnBoot: Boolean = true,
    val liveNotification: Boolean = true,
    val threatAlerts: Boolean = true,
    val recordActivity: Boolean = true,
    val autoUpdateLists: Boolean = true,
    val updateOnUnmeteredOnly: Boolean = true,
    val secureScreen: Boolean = false,
    // Appearance
    val theme: ThemeId = ThemeId.HIGHLAND_MIDNIGHT,
    val nightTheme: ThemeId? = null,
    val brightness: Brightness = Brightness.SYSTEM,
    val scheduleDarkFromMinutes: Int = 20 * 60,
    val scheduleDarkUntilMinutes: Int = 7 * 60,
    val customAccent: Int? = null,
    val motion: MotionPref = MotionPref.SYSTEM,
    val effects: EffectsPref = EffectsPref.AUTO,
    val widgets: List<WidgetSlot> = Widget.entries.map { WidgetSlot(it, true) },
    // Lifecycle
    val wantsProtection: Boolean = false,
    val onboarded: Boolean = false,
)

/**
 * SharedPreferences-backed settings exposed as a StateFlow. Writes are applied
 * asynchronously; readers always see the in-memory value immediately.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<Settings> = state.asStateFlow()
    val value: Settings get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        write(next)
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private fun read(): Settings {
        val d = Settings()
        val p = prefs
        return Settings(
            providerId = p.getString("provider", d.providerId)!!,
            fallbackId = if (p.contains("fallback")) p.getString("fallback", null)?.ifEmpty { null } else d.fallbackId,
            protocol = enumOf("protocol", d.protocol),
            plaintextFallback = p.getBoolean("plaintextFallback", d.plaintextFallback),
            categories = p.getStringSet("categories", null)
                ?.mapNotNull { runCatching { Category.valueOf(it) }.getOrNull() }?.toSet() ?: d.categories,
            blockResponse = enumOf("blockResponse", d.blockResponse),
            cnameUncloaking = p.getBoolean("cname", d.cnameUncloaking),
            bypassCanary = p.getBoolean("canary", d.bypassCanary),
            userBlocked = p.getStringSet("userBlocked", null)?.toSet() ?: d.userBlocked,
            userAllowed = p.getStringSet("userAllowed", null)?.toSet() ?: d.userAllowed,
            gateMode = enumOf("gateMode", d.gateMode),
            identifyApps = p.getBoolean("identifyApps", d.identifyApps),
            startOnBoot = p.getBoolean("startOnBoot", d.startOnBoot),
            liveNotification = p.getBoolean("liveNotification", d.liveNotification),
            threatAlerts = p.getBoolean("threatAlerts", d.threatAlerts),
            recordActivity = p.getBoolean("recordActivity", d.recordActivity),
            autoUpdateLists = p.getBoolean("autoUpdate", d.autoUpdateLists),
            updateOnUnmeteredOnly = p.getBoolean("unmeteredOnly", d.updateOnUnmeteredOnly),
            secureScreen = p.getBoolean("secureScreen", d.secureScreen),
            theme = enumOf("theme", d.theme),
            nightTheme = p.getString("nightTheme", null)?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() },
            brightness = enumOf("brightness", d.brightness),
            scheduleDarkFromMinutes = p.getInt("darkFrom", d.scheduleDarkFromMinutes),
            scheduleDarkUntilMinutes = p.getInt("darkUntil", d.scheduleDarkUntilMinutes),
            customAccent = if (p.contains("accent")) p.getInt("accent", 0) else null,
            motion = enumOf("motion", d.motion),
            effects = enumOf("effects", d.effects),
            widgets = parseWidgets(p.getString("widgets", null)) ?: d.widgets,
            wantsProtection = p.getBoolean("wantsProtection", d.wantsProtection),
            onboarded = p.getBoolean("onboarded", d.onboarded),
        )
    }

    private fun parseWidgets(s: String?): List<WidgetSlot>? {
        if (s.isNullOrBlank()) return null
        val parsed = s.split(',').mapNotNull { token ->
            val visible = !token.startsWith("-")
            runCatching { WidgetSlot(Widget.valueOf(token.removePrefix("-")), visible) }.getOrNull()
        }
        // Append any widget added in a newer version.
        val missing = Widget.entries.filter { w -> parsed.none { it.widget == w } }.map { WidgetSlot(it, true) }
        return parsed + missing
    }

    private fun write(s: Settings) = prefs.edit {
        putString("provider", s.providerId)
        putString("fallback", s.fallbackId ?: "")
        putString("protocol", s.protocol.name)
        putBoolean("plaintextFallback", s.plaintextFallback)
        putStringSet("categories", s.categories.map { it.name }.toSet())
        putString("blockResponse", s.blockResponse.name)
        putBoolean("cname", s.cnameUncloaking)
        putBoolean("canary", s.bypassCanary)
        putStringSet("userBlocked", s.userBlocked)
        putStringSet("userAllowed", s.userAllowed)
        putString("gateMode", s.gateMode.name)
        putBoolean("identifyApps", s.identifyApps)
        putBoolean("startOnBoot", s.startOnBoot)
        putBoolean("liveNotification", s.liveNotification)
        putBoolean("threatAlerts", s.threatAlerts)
        putBoolean("recordActivity", s.recordActivity)
        putBoolean("autoUpdate", s.autoUpdateLists)
        putBoolean("unmeteredOnly", s.updateOnUnmeteredOnly)
        putBoolean("secureScreen", s.secureScreen)
        putString("theme", s.theme.name)
        if (s.nightTheme == null) remove("nightTheme") else putString("nightTheme", s.nightTheme.name)
        putString("brightness", s.brightness.name)
        putInt("darkFrom", s.scheduleDarkFromMinutes)
        putInt("darkUntil", s.scheduleDarkUntilMinutes)
        if (s.customAccent == null) remove("accent") else putInt("accent", s.customAccent)
        putString("motion", s.motion.name)
        putString("effects", s.effects.name)
        putString("widgets", s.widgets.joinToString(",") { (if (it.visible) "" else "-") + it.widget.name })
        putBoolean("wantsProtection", s.wantsProtection)
        putBoolean("onboarded", s.onboarded)
    }
}
