package com.rocksguard.app

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.rocksguard.app.data.ActivityEntry
import com.rocksguard.app.data.CompileState
import com.rocksguard.app.data.Counters
import com.rocksguard.app.data.FilterRepository
import com.rocksguard.app.data.InstalledApp
import com.rocksguard.app.data.ListMeta
import com.rocksguard.app.data.ListState
import com.rocksguard.app.data.Settings
import com.rocksguard.app.data.StatsSnapshot
import com.rocksguard.app.ui.AppRoot
import com.rocksguard.app.ui.Check
import com.rocksguard.app.ui.CheckStatus
import com.rocksguard.app.ui.Dest
import com.rocksguard.app.ui.screens.ArmouryScreen
import com.rocksguard.app.ui.screens.FortressScreen
import com.rocksguard.app.ui.screens.FortressUi
import com.rocksguard.app.ui.screens.KeepScreen
import com.rocksguard.app.ui.screens.LookoutScreen
import com.rocksguard.app.ui.screens.RampartsScreen
import com.rocksguard.app.ui.screens.ShieldScreen
import com.rocksguard.app.ui.screens.WatchtowerScreen
import com.rocksguard.app.ui.screens.WorkshopScreen
import com.rocksguard.app.ui.theme.PreviewTheme
import com.rocksguard.app.vpn.GuardState
import com.rocksguard.app.vpn.Phase
import com.rocksguard.core.brand.ThemeId
import com.rocksguard.core.dns.DnsType
import com.rocksguard.core.engine.AppRule
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.engine.NetworkKind
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.SourceInfo
import com.rocksguard.core.filter.Verdict
import com.rocksguard.core.upstream.TransportHealth
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real RocksGuard screens on the JVM (Robolectric native graphics) with sample
 * data, so the design can be reviewed without a device. Output: docs/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {

    private val now = 1_763_400_000_000L

    private val running = GuardState(
        phase = Phase.RUNNING, mode = GateMode.WATCH, startedAt = now - 3_600_000, network = NetworkKind.WIFI,
        upstream = listOf(
            TransportHealth("Quad9 · HTTPS", true, 24, 0, 1832, true),
            TransportHealth("Cloudflare · HTTPS", true, null, 0, 0, true),
        ),
        restrictedApps = 3, alwaysOn = true, lockdown = false, cacheEntries = 412, cacheHitRate = 0.61,
    )

    private val stats = StatsSnapshot(
        today = Counters(total = 4_812, adverts = 1_204, trackers = 862, threats = 7, bypass = 2, firewall = 41, user = 3, cname = 19, cached = 2_931, encrypted = 1_880, failed = 1),
        lifetime = Counters(total = 120_000),
        perMinute = IntArray(60) { i -> listOf(4, 9, 22, 13, 6, 31, 18, 2, 0, 11)[i % 10] + i / 6 },
        perMinuteBlocked = IntArray(60) { i -> listOf(1, 3, 9, 5, 2, 12, 6, 1, 0, 4)[i % 10] },
        topBlocked = listOf("app-measurement.com" to 211, "graph.instagram.com" to 140, "ads.example-network.com" to 96, "metrics.icloud-cdn.net" to 41),
        topApps = listOf(Triple(10123, 412, 1300), Triple(10155, 120, 600)),
    )

    private val lightSettings = Settings(onboarded = true)

    private fun shoot(name: String, theme: ThemeId = ThemeId.HIGHLAND_MIDNIGHT, dark: Boolean = true, content: @Composable () -> Unit) =
        captureRoboImage("${System.getProperty("roborazzi.output.dir")}/$name.png") {
            PreviewTheme(theme, dark) { content() }
        }

    @Composable
    private fun Fortress(guard: GuardState = running, scanning: Boolean = false) =
        AppRoot(Dest.FORTRESS) { _, _ ->
            FortressScreen(FortressUi(guard, stats, lightSettings, 412_380), scanning, 0, {}, {}, {}, {})
        }

    @Test fun fortressActive() = shoot("01_fortress_active") { Fortress() }

    @Test fun fortressInactive() = shoot("02_fortress_inactive") {
        Fortress(GuardState(phase = Phase.STOPPED))
    }

    @Test fun fortressAllThemes() {
        ThemeId.entries.forEach { t -> shoot("03_theme_${t.name.lowercase()}", t, dark = true) { Fortress() } }
        shoot("03_theme_highland_winter_light", ThemeId.HIGHLAND_WINTER, dark = false) { Fortress() }
        shoot("03_theme_royal_edinburgh_light", ThemeId.ROYAL_EDINBURGH, dark = false) { Fortress() }
    }

    private fun entry(id: Long, name: String, v: Verdict, uid: Int = 10123, cached: Boolean = false, ms: Long = 18) =
        ActivityEntry(id, now - id * 7_000, name, DnsType.A, uid, v, "Quad9 · HTTPS", true, ms, cached, false)

    @Test fun lookout() = shoot("04_lookout") {
        val trk = SourceInfo("hagezi-light", "HaGeZi Multi Light", Category.ADS)
        val threat = SourceInfo("tif", "HaGeZi Threat Intelligence (mini)", Category.THREATS)
        val entries = listOf(
            entry(1, "graph.instagram.com", Verdict.BlockedByList("graph.instagram.com", Category.TRACKERS, SourceInfo("ep", "EasyPrivacy (domains)", Category.TRACKERS), false)),
            entry(2, "www.bbc.co.uk", Verdict.Allowed, cached = true, ms = 0),
            entry(3, "secure-login.paypa1-verify.top", Verdict.BlockedByList("paypa1-verify.top", Category.THREATS, threat, true), uid = 10155),
            entry(4, "metrics.shop.example", Verdict.BlockedCname("collect.tracker.example", Verdict.BlockedByList("tracker.example", Category.TRACKERS, trk, true))),
            entry(5, "pagead2.googlesyndication.com", Verdict.BlockedByList("googlesyndication.com", Category.ADS, trk, true)),
            entry(6, "api.github.com", Verdict.Allowed, ms = 31),
            entry(7, "use-application-dns.net", Verdict.BlockedCanary("canary"), uid = 10200),
            entry(8, "push.social.example", Verdict.BlockedByFirewall(10300), uid = 10300),
            entry(9, "fonts.gstatic.com", Verdict.Allowed, cached = true, ms = 0),
        )
        AppRoot(Dest.LOOKOUT) { _, _ ->
            LookoutScreen(entries, true, false, { uid -> mapOf(10123 to "Instagram", 10155 to "Messages", 10200 to "Firefox", 10300 to "Social")[uid] }, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun ramparts() = shoot("05_ramparts") {
        val apps = listOf(
            InstalledApp("com.example.social", "Social", 10300, false, true),
            InstalledApp("com.example.game", "Highland Quest", 10301, false, true),
            InstalledApp("com.example.bank", "Clydesdale Bank", 10302, false, true),
            InstalledApp("com.example.maps", "Maps", 10303, false, true),
            InstalledApp("com.example.notes", "Notes", 10304, false, false),
            InstalledApp("com.android.vending", "Play Store", 10305, true, true),
        )
        val rules = mapOf(
            "com.example.social" to AppRule("com.example.social", 10300, blockOnWifi = true, blockOnMobile = true),
            "com.example.game" to AppRule("com.example.game", 10301, blockOnMobile = true),
            "com.example.bank" to AppRule("com.example.bank", 10302, bypass = true),
        )
        AppRoot(Dest.RAMPARTS) { _, _ -> RampartsScreen(apps, rules, GateMode.WATCH, true, { _, _ -> null }, {}, { _, _ -> }) }
    }

    @Test fun watchtower() = shoot("06_watchtower") {
        AppRoot(Dest.WATCHTOWER) { _, _ ->
            WatchtowerScreen(lightSettings, running, mapOf("quad9" to 24L, "cloudflare" to 12L, "cloudflare-security" to 14L, "mullvad" to 41L, "dns4eu" to 38L, "adguard" to 52L, "google" to 19L), false, {}, {}, {}, {}, {})
        }
    }

    @Test fun shield() = shoot("07_shield") {
        AppRoot(Dest.SHIELD) { _, _ ->
            ShieldScreen(lightSettings.copy(userBlocked = setOf("tiktokv.com"), userAllowed = setOf("ads.allowed-partner.example")), stats.today, { _, _ -> }, {}, {}, {}, { _, _ -> null }, { _, _ -> })
        }
    }

    @Test fun armoury() = shoot("08_armoury") {
        val lists = FilterRepository.CATALOGUE.map { l ->
            ListState(l, l.defaultEnabled, ListMeta(updatedAt = now - 3_600_000, rules = if (l.url == null) 60 else 120_000), true)
        }
        AppRoot(Dest.ARMOURY) { _, _ -> ArmouryScreen(lists, CompileState(totalDomains = 412_380, lastCompiledAt = now), { _, _ -> }, {}, { _, _, _ -> null }, {}) }
    }

    @Test fun keep() = shoot("09_keep") {
        val checks = listOf(
            Check("Private DNS bypasses RocksGuard", "Android's Private DNS is set to dns.example, which skips filtering.", CheckStatus.WARN, "Open network settings", android.content.Intent()),
            Check("Battery optimisation applies", "Exempting RocksGuard keeps the guard standing.", CheckStatus.INFO),
            Check("The Guard Stands", "Protection is running in Watch Gate mode.", CheckStatus.OK),
            Check("DNS encrypted", "Quad9 answered over HTTPS in 24 ms.", CheckStatus.OK),
            Check("Blocklists loaded", "412,380 domains guarded from 5 lists.", CheckStatus.OK),
        )
        AppRoot(Dest.KEEP) { _, _ -> KeepScreen(checks, false, stats, lightSettings, { "Instagram" }, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun workshop() = shoot("10_workshop") {
        AppRoot(Dest.WORKSHOP) { _, _ -> WorkshopScreen(lightSettings, "1.0.0", {}, {}) }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun tablet() = shoot("11_tablet_fortress") { Fortress() }

    @Test @Config(qualifiers = "w400dp-h880dp-xxhdpi", fontScale = 1.6f)
    fun largeFont() = shoot("12_large_font_fortress") { Fortress() }
}
