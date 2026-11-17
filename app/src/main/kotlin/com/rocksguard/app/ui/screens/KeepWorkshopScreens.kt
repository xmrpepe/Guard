package com.rocksguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.Brightness
import com.rocksguard.app.data.EffectsPref
import com.rocksguard.app.data.MotionPref
import com.rocksguard.app.data.Settings
import com.rocksguard.app.data.StatsSnapshot
import com.rocksguard.app.ui.Check
import com.rocksguard.app.ui.CheckStatus
import com.rocksguard.app.ui.brand.MarkIcon
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.Note
import com.rocksguard.app.ui.components.Panel
import com.rocksguard.app.ui.components.ProportionBar
import com.rocksguard.app.ui.components.SaltireButton
import com.rocksguard.app.ui.components.ScreenHeader
import com.rocksguard.app.ui.components.SectionLabel
import com.rocksguard.app.ui.components.Segmented
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.components.StoneShape
import com.rocksguard.app.ui.components.TartanBand
import com.rocksguard.app.ui.components.ToggleRow
import com.rocksguard.app.ui.components.formatCount
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.ui.theme.RgColors
import com.rocksguard.core.brand.Palettes
import com.rocksguard.core.brand.ThemeId

// ===================================================================== The Keep

@Composable
fun CheckRow(check: Check, onAction: (Check) -> Unit) {
    val c = Rg.colors
    val (color, glyph, word) = when (check.status) {
        CheckStatus.OK -> Triple(c.protectionText, RgIcons.Check, "Secure")
        CheckStatus.WARN -> Triple(c.premiumText, RgIcons.Warning, "Attention")
        CheckStatus.FAIL -> Triple(c.alertText, RgIcons.Cross, "Problem")
        CheckStatus.INFO -> Triple(c.accentText, RgIcons.Info, "Suggestion")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$word: ${check.title}. ${check.detail}" },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(28.dp).clip(SmallStone).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(glyph, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            if (check.actionLabel != null && check.action != null) {
                Spacer(Modifier.height(6.dp))
                SaltireButton(check.actionLabel, { onAction(check) }, filled = false)
            }
        }
    }
}

@Composable
fun KeepScreen(
    checks: List<Check>?,
    inspecting: Boolean,
    stats: StatsSnapshot,
    settings: Settings,
    appLabel: (Int) -> String,
    onInspect: () -> Unit,
    onAction: (Check) -> Unit,
    onClearActivity: () -> Unit,
    onResetStats: () -> Unit,
    onRecord: (Boolean) -> Unit,
    onSecureScreen: (Boolean) -> Unit,
) {
    val c = Rg.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader("Privacy centre", "The Keep", RgIcons.Keep) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("FORTRESS INSPECTION", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.weight(1f))
                SaltireButton(if (inspecting) "Inspecting…" else "Inspect again", onInspect, filled = false, icon = RgIcons.Radar, enabled = !inspecting)
            }
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                when {
                    checks == null -> Text("Walking the walls…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                    else -> {
                        val ok = checks.count { it.status == CheckStatus.OK }
                        Text("$ok of ${checks.size} checks secure", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
                        checks.forEach { CheckRow(it, onAction) }
                    }
                }
            }
        }
        item {
            SectionLabel("Privacy report · today")
            val t = stats.today
            Panel(Modifier.fillMaxWidth()) {
                val pct = if (t.total == 0L) 0f else t.blocked.toFloat() / t.total
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Text("of ${formatCount(t.total)} lookups were blocked", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                ProportionBar(pct, c.premium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${formatCount(t.encrypted)} sent encrypted · ${formatCount(t.cached)} answered from cache · ${formatCount(t.failed)} failed",
                    style = MaterialTheme.typography.labelMedium, color = c.textTertiary,
                )
            }
        }
        if (stats.topBlocked.isNotEmpty()) {
            item { SectionLabel("Most blocked since the guard was raised") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    val max = stats.topBlocked.first().second.toFloat()
                    stats.topBlocked.forEach { (d, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatCount(n.toLong()), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        }
                        ProportionBar(n / max, c.privacy, height = 3.dp)
                    }
                }
            }
        }
        if (stats.topApps.any { it.second > 0 }) {
            item { SectionLabel("Apps most often stopped") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    stats.topApps.filter { it.second > 0 }.forEach { (uid, blocked, total) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(appLabel(uid), style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatCount(blocked.toLong())} of ${formatCount(total.toLong())}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        }
                    }
                }
            }
        }
        item {
            SectionLabel("Your data")
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleRow("Record activity", "Lists recent lookups in the Lookout. Held in memory only and cleared when the guard stands down.", settings.recordActivity, onRecord, icon = RgIcons.Lookout)
                ToggleRow("Hide screen contents", "Blocks screenshots and hides RocksGuard in the recent-apps view.", settings.secureScreen, onSecureScreen, icon = RgIcons.Keep)
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SaltireButton("Clear activity", onClearActivity, filled = false, icon = RgIcons.Trash)
                    SaltireButton("Reset counters", onResetStats, filled = false, icon = RgIcons.Refresh)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(RgIcons.Thistle, null, tint = c.privacyText, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "No account. No adverts. No analytics. RocksGuard sends nothing about you anywhere: lookups go only to the resolver you choose, and lists come straight from their publishers.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
        }
    }
}

// ===================================================================== The Workshop

private val ACCENTS = listOf(0xFF0065BD, 0xFF8064A2, 0xFF287C63, 0xFFD5AA62, 0xFFC44545, 0xFF2F6FA7, 0xFF00897B, 0xFFE07A2F).map { it.toInt() }

@Composable
fun WorkshopScreen(
    settings: Settings,
    version: String,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onArrange: () -> Unit,
) {
    val c = Rg.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item { ScreenHeader("Settings", "The Workshop", RgIcons.Workshop) }
        item {
            SectionLabel("Theme")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeId.entries.forEach { t -> ThemeCard(t, settings.theme == t) { onUpdate { s -> s.copy(theme = t) } } }
            }
        }
        item {
            SectionLabel("Brightness")
            Segmented(
                listOf(Brightness.SYSTEM to "System", Brightness.LIGHT to "Light", Brightness.DARK to "Dark", Brightness.SCHEDULED to "Schedule"),
                settings.brightness, { b -> onUpdate { it.copy(brightness = b) } },
            )
            if (ThemeId.entries.first { it == settings.theme }.darkOnly) {
                Note("Fortress Black is always dark, as AMOLED black only saves power in the dark.", modifier = Modifier.padding(top = 8.dp))
            }
            if (settings.brightness == Brightness.SCHEDULED) {
                Panel(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    TimeStepper("Dark from", settings.scheduleDarkFromMinutes) { m -> onUpdate { it.copy(scheduleDarkFromMinutes = m) } }
                    TimeStepper("Light from", settings.scheduleDarkUntilMinutes) { m -> onUpdate { it.copy(scheduleDarkUntilMinutes = m) } }
                }
            }
            if (settings.brightness != Brightness.LIGHT) {
                SectionLabel("Night theme")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.rocksguard.app.ui.components.FilterChipRg("Same as day", settings.nightTheme == null, { onUpdate { it.copy(nightTheme = null) } })
                    ThemeId.entries.filter { it != settings.theme }.forEach { t ->
                        com.rocksguard.app.ui.components.FilterChipRg(t.title, settings.nightTheme == t, { onUpdate { it.copy(nightTheme = t) } })
                    }
                }
            }
        }
        item {
            SectionLabel("Accent colour")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(null, settings.customAccent == null, "Theme default") { onUpdate { it.copy(customAccent = null) } }
                ACCENTS.forEach { a -> Swatch(Color(a), settings.customAccent == a, "Accent ${Integer.toHexString(a).takeLast(6)}") { onUpdate { it.copy(customAccent = a) } } }
            }
            Note("Text and icons are automatically adjusted so every accent stays readable (WCAG AA).", modifier = Modifier.padding(top = 8.dp))
        }
        item {
            SectionLabel("Motion and effects")
            Segmented(listOf(MotionPref.SYSTEM to "Follow system", MotionPref.REDUCED to "Reduced", MotionPref.FULL to "Full"), settings.motion, { m -> onUpdate { it.copy(motion = m) } })
            Spacer(Modifier.height(10.dp))
            Segmented(listOf(EffectsPref.AUTO to "Auto", EffectsPref.FULL to "Rich", EffectsPref.LITE to "Lite"), settings.effects, { e -> onUpdate { it.copy(effects = e) } })
            Note("Auto picks Lite effects on entry-level phones. Protection is identical on every setting.", modifier = Modifier.padding(top = 8.dp))
        }
        item {
            SectionLabel("Dashboard")
            SaltireButton("Arrange the Fortress widgets", onArrange, filled = false, icon = RgIcons.Fortress)
        }
        item {
            SectionLabel("Behaviour")
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleRow("Raise on start-up", "Restore protection after a restart or update if it was on.", settings.startOnBoot, { v -> onUpdate { it.copy(startOnBoot = v) } }, icon = RgIcons.Shield)
                ToggleRow("Live protection notification", "Show today's blocked count in the status notification.", settings.liveNotification, { v -> onUpdate { it.copy(liveNotification = v) } }, icon = RgIcons.Info)
                ToggleRow("Threat alerts", "Notify when a malicious or phishing domain is blocked (at most every 20 s).", settings.threatAlerts, { v -> onUpdate { it.copy(threatAlerts = v) } }, icon = RgIcons.Warning, iconTint = c.alertText)
                ToggleRow("Identify apps", "Name the app behind each lookup (Android 10+). Needed for Watch Gate app rules.", settings.identifyApps, { v -> onUpdate { it.copy(identifyApps = v) } }, icon = RgIcons.Lookout)
                ToggleRow("Update lists automatically", "About once a day, only when the battery is not low.", settings.autoUpdateLists, { v -> onUpdate { it.copy(autoUpdateLists = v) } }, icon = RgIcons.Refresh)
                ToggleRow("Only on Wi‑Fi", "Avoid using mobile data for list updates.", settings.updateOnUnmeteredOnly, { v -> onUpdate { it.copy(updateOnUnmeteredOnly = v) } }, icon = RgIcons.Wifi, enabled = settings.autoUpdateLists)
            }
        }
        item {
            SectionLabel("About")
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkIcon(Modifier.size(48.dp, 58.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("ROCKSGUARD", style = MaterialTheme.typography.headlineSmall, color = c.text)
                        Text("The Scottish Guardian · $version", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                TartanBand(Modifier.fillMaxWidth().height(6.dp))
                Spacer(Modifier.height(12.dp))
                Text("Forged in Scotland. Built for Privacy.", style = MaterialTheme.typography.titleMedium, color = c.premiumText)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open-source parts: OkHttp (Apache 2.0), AndroidX and Jetpack Compose (Apache 2.0), Cinzel typeface by Natanael Gama (SIL Open Font License 1.1). Filter lists remain the work of their publishers under the licences shown in the Armoury.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(t: ThemeId, selected: Boolean, onClick: () -> Unit) {
    val c = Rg.colors
    val p = remember(t) { RgColors.from(Palettes.of(t, true)) }
    Column(
        Modifier.width(150.dp).clip(StoneShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accentText else c.outline, StoneShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${t.title} theme. ${t.description}" },
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(p.background), contentAlignment = Alignment.Center) {
            MarkIcon(Modifier.size(52.dp, 62.dp), colors = p)
            if (p.tartan.isNotEmpty()) TartanBand(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp), colors = p)
        }
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(p.accent, p.protection, p.privacy, p.premium, p.metalMid).forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(it)) }
        }
        Column(Modifier.fillMaxWidth().background(c.surface).padding(10.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.description, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = c.textSecondary, minLines = 2, maxLines = 2)
        }
    }
}

@Composable
private fun Swatch(color: Color?, selected: Boolean, label: String, onClick: () -> Unit) {
    val c = Rg.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(color ?: c.surfaceRaised)
            .border(if (selected) 3.dp else 1.dp, if (selected) c.text else c.outline, CircleShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Text("A", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, textAlign = TextAlign.Center)
        if (selected && color != null) Icon(RgIcons.Check, null, tint = if (color.toArgb().let { com.rocksguard.core.brand.ColorMath.luminance(it) } > 0.4) Color.Black else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    val c = Rg.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
        IconButton({ onChange((minutes - 30 + 1440) % 1440) }, modifier = Modifier.semantics { contentDescription = "$label 30 minutes earlier" }) {
            Icon(RgIcons.Down, null, tint = c.accentText)
        }
        Text("%02d:%02d".format(minutes / 60, minutes % 60), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.text)
        IconButton({ onChange((minutes + 30) % 1440) }, modifier = Modifier.semantics { contentDescription = "$label 30 minutes later" }) {
            Icon(RgIcons.Up, null, tint = c.accentText)
        }
    }
}
