package com.rocksguard.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.Settings
import com.rocksguard.app.data.StatsSnapshot
import com.rocksguard.app.data.Widget
import com.rocksguard.app.data.WidgetSlot
import com.rocksguard.app.ui.Dest
import com.rocksguard.app.ui.brand.FortressEmblem
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.brand.SystemsLit
import com.rocksguard.app.ui.components.ActivityBars
import com.rocksguard.app.ui.components.GateIndicator
import com.rocksguard.app.ui.components.Lamp
import com.rocksguard.app.ui.components.Panel
import com.rocksguard.app.ui.components.SaltireButton
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.components.TartanBand
import com.rocksguard.app.ui.components.formatCount
import com.rocksguard.app.ui.theme.Cinzel
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.vpn.GuardState
import com.rocksguard.app.vpn.Phase
import com.rocksguard.core.brand.ThemeId
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.upstream.DnsProtocol

data class FortressUi(
    val guard: GuardState,
    val stats: StatsSnapshot,
    val settings: Settings,
    val totalDomains: Int,
)

private data class Status(val title: String, val subtitle: String, val detail: String, val detailColor: Color)

@Composable
private fun status(ui: FortressUi): Status {
    val c = Rg.colors
    val g = ui.guard
    return when (g.phase) {
        Phase.STARTING -> Status("RAISING THE GUARD", "Protection starting", "Building the tunnel and loading your rules…", c.textSecondary)
        Phase.ERROR, Phase.STOPPED -> Status(
            "THE GUARD IS DOWN", "Protection Inactive",
            g.error ?: "Tap the shield to raise the guard. The Guard Rests until then.",
            if (g.error != null) c.alertText else c.textSecondary,
        )
        Phase.RUNNING -> {
            val issues = buildList {
                if (!g.upstreamHealthy) add("Resolver unreachable — trying the fallback")
                if (g.privateDnsStrict != null) add("Android Private DNS is bypassing the guard")
                if (ui.totalDomains < 1000) add("Fetching the full blocklists…")
                g.notice?.let { add(it) }
            }
            if (issues.isEmpty()) {
                Status("THE GUARD STANDS", "Protection Active", "Fortress Secured — all configured protections operational", c.protectionText)
            } else {
                Status("THE GUARD STANDS", "Protection Active", issues.first(), c.premiumText)
            }
        }
    }
}

@Composable
fun FortressScreen(
    ui: FortressUi,
    scanning: Boolean,
    pulseKey: Long,
    onToggle: () -> Unit,
    onInspect: () -> Unit,
    onOpen: (Dest) -> Unit,
    onWidgets: (List<WidgetSlot>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
                    item { Wordmark(onInspect, scanning) }
                    item { Command(ui, scanning, pulseKey, onToggle) }
                }
                Spacer(Modifier.width(24.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 24.dp)) {
                    widgets(ui, editing, { editing = it }, onOpen, onWidgets)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
                item { Wordmark(onInspect, scanning) }
                item { Command(ui, scanning, pulseKey, onToggle) }
                widgets(ui, editing, { editing = it }, onOpen, onWidgets)
            }
        }
    }
}

@Composable
private fun Wordmark(onInspect: () -> Unit, scanning: Boolean) {
    val c = Rg.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ROCKSGUARD", fontFamily = Cinzel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = MaterialTheme.typography.headlineSmall.letterSpacing * 6), color = c.text)
                Text("Your Digital Fortress", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            IconButton(onClick = onInspect, enabled = !scanning, modifier = Modifier.semantics { contentDescription = "Inspect the fortress: run a security scan" }) {
                Icon(RgIcons.Radar, null, tint = if (scanning) c.textTertiary else c.accentText)
            }
        }
        if (Rg.theme == ThemeId.SCOTTISH_HERITAGE) TartanBand(Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp))
    }
}

@Composable
private fun Command(ui: FortressUi, scanning: Boolean, pulseKey: Long, onToggle: () -> Unit) {
    val c = Rg.colors
    val g = ui.guard
    val running = g.running
    val st = status(ui)
    val systems = SystemsLit(
        filters = running && g.mode == GateMode.WATCH && ui.settings.categories.isNotEmpty(),
        dns = running && g.mode == GateMode.WATCH && ui.settings.protocol != DnsProtocol.PLAIN && g.upstreamHealthy,
        firewall = running && (g.mode == GateMode.SEALED || g.restrictedApps > 0),
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.padding(top = 4.dp).widthIn(max = 340.dp).fillMaxWidth(0.88f).aspectRatio(1f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    role = Role.Switch,
                    onClick = onToggle,
                )
                .semantics {
                    contentDescription = "RocksGuard shield"
                    stateDescription = if (running) "Protection on. Double-tap to stand down." else "Protection off. Double-tap to raise the guard."
                },
        ) {
            FortressEmblem(active = running || g.phase == Phase.STARTING, systems = systems, pulseKey = pulseKey, scanning = scanning, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.title, style = MaterialTheme.typography.displaySmall, color = if (running) c.text else c.textSecondary, textAlign = TextAlign.Center)
            Text(st.subtitle, style = MaterialTheme.typography.titleMedium, color = if (running) c.protectionText else c.textSecondary)
            Spacer(Modifier.height(6.dp))
            Text(st.detail, style = MaterialTheme.typography.bodySmall, color = st.detailColor, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        SaltireButton(
            text = if (running || g.phase == Phase.STARTING) "Stand down" else "Raise the guard",
            onClick = onToggle,
            filled = !running,
            icon = if (running) RgIcons.Pause else RgIcons.Shield,
            modifier = Modifier.widthIn(min = 220.dp),
        )
        Spacer(Modifier.height(20.dp))
        Legend(systems)
    }
}

@Composable
private fun Legend(s: SystemsLit) {
    val c = Rg.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
        listOf(Triple("Filters", c.protection, s.filters), Triple("Encrypted DNS", c.accentText, s.dns), Triple("Firewall", c.privacyText, s.firewall))
            .forEach { (label, color, on) ->
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lamp(on, color, Modifier.size(8.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.textSecondary else c.textTertiary)
                }
            }
    }
}

private val COUNTERS = setOf(Widget.ADVERTS, Widget.TRACKERS, Widget.THREATS)

private fun LazyListScope.widgets(
    ui: FortressUi,
    editing: Boolean,
    setEditing: (Boolean) -> Unit,
    onOpen: (Dest) -> Unit,
    onWidgets: (List<WidgetSlot>) -> Unit,
) {
    val slots = ui.settings.widgets
    if (editing) {
        item { Text("ARRANGE THE BATTLEMENTS", style = MaterialTheme.typography.labelSmall, color = Rg.colors.textTertiary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) }
        slots.forEachIndexed { i, slot ->
            item(key = "edit-${slot.widget}") { EditRow(slots, i, onWidgets) }
        }
    } else {
        val visible = slots.filter { it.visible }.map { it.widget }
        // Consecutive counter widgets share one row of battlement tiles.
        val groups = ArrayList<List<Widget>>()
        for (w in visible) {
            if (w in COUNTERS && groups.lastOrNull()?.all { it in COUNTERS } == true && groups.last().size < 3) groups[groups.lastIndex] = groups.last() + w
            else groups += listOf(w)
        }
        item { Spacer(Modifier.height(16.dp)) }
        for (g in groups) {
            item(key = g.joinToString()) {
                if (g.first() in COUNTERS) CounterRow(g, ui.stats, onOpen)
                else when (g.first()) {
                    Widget.DNS -> DnsWidget(ui, onOpen)
                    Widget.FIREWALL -> FirewallWidget(ui, onOpen)
                    Widget.ACTIVITY -> ActivityWidget(ui.stats, onOpen)
                    else -> Unit
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            SaltireButton(if (editing) "Done" else "Arrange dashboard", { setEditing(!editing) }, filled = false, icon = if (editing) RgIcons.Check else RgIcons.Workshop)
        }
    }
}

@Composable
private fun EditRow(slots: List<WidgetSlot>, i: Int, onWidgets: (List<WidgetSlot>) -> Unit) {
    val c = Rg.colors
    val slot = slots[i]
    fun move(to: Int) {
        if (to !in slots.indices) return
        val m = slots.toMutableList(); val x = m.removeAt(i); m.add(to, x); onWidgets(m)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(SmallStone).background(c.surface).border(1.dp, c.outline, SmallStone).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slot.widget.title, style = MaterialTheme.typography.titleMedium, color = if (slot.visible) c.text else c.textTertiary, modifier = Modifier.weight(1f))
        IconButton({ move(i - 1) }, enabled = i > 0, modifier = Modifier.semantics { contentDescription = "Move ${slot.widget.title} up" }) { Icon(RgIcons.Up, null, tint = c.textSecondary) }
        IconButton({ move(i + 1) }, enabled = i < slots.lastIndex, modifier = Modifier.semantics { contentDescription = "Move ${slot.widget.title} down" }) { Icon(RgIcons.Down, null, tint = c.textSecondary) }
        IconButton(
            { onWidgets(slots.toMutableList().also { it[i] = slot.copy(visible = !slot.visible) }) },
            modifier = Modifier.semantics { contentDescription = (if (slot.visible) "Hide " else "Show ") + slot.widget.title },
        ) { Icon(if (slot.visible) RgIcons.Lookout else RgIcons.Cross, null, tint = if (slot.visible) c.accentText else c.textTertiary) }
    }
}

/** Tile shape with three merlons along its top edge, like a curtain wall. */
private val Battlement = GenericShape { size, _ ->
    val d = size.height * 0.07f
    val seg = size.width / 7f
    moveTo(0f, 0f)
    var x = 0f
    for (i in 0 until 7) {
        val top = if (i % 2 == 0) 0f else d
        lineTo(x, top); x += seg; lineTo(x, top)
    }
    lineTo(size.width, size.height); lineTo(0f, size.height); close()
}

@Composable
private fun CounterRow(widgets: List<Widget>, stats: StatsSnapshot, onOpen: (Dest) -> Unit) {
    val c = Rg.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (w in widgets) {
            val (value, color, label) = when (w) {
                Widget.ADVERTS -> Triple(stats.today.adverts, c.premiumText, "Adverts\nBlocked")
                Widget.TRACKERS -> Triple(stats.today.trackers, c.privacyText, "Trackers\nStopped")
                else -> Triple(stats.today.threats, c.alertText, "Threats\nPrevented")
            }
            Counter(value, label, color, Modifier.weight(1f)) { onOpen(Dest.LOOKOUT) }
        }
    }
}

@Composable
private fun Counter(value: Long, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Rg.colors
    val reduced = Rg.motion.reduced
    val shown by animateFloatAsState(value.toFloat(), if (reduced) snap() else tween(700), label = "count")
    Column(
        modifier.clip(Battlement).background(c.surface).border(1.dp, c.outline, Battlement)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${label.replace('\n', ' ')} today: $value" }
            .padding(start = 12.dp, end = 8.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Text(
            formatCount(if (reduced) value else shown.toLong()),
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", fontFamily = null, fontWeight = FontWeight.SemiBold),
            color = color, maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, minLines = 2)
    }
}

@Composable
private fun WidgetHeader(title: String, value: String, valueColor: Color, lead: @Composable () -> Unit) {
    val c = Rg.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        lead()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
        Icon(RgIcons.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DnsWidget(ui: FortressUi, onOpen: (Dest) -> Unit) {
    val c = Rg.colors
    val g = ui.guard
    val encrypted = ui.settings.protocol != DnsProtocol.PLAIN
    val active = g.activeUpstream
    val value = when {
        !g.running -> "Idle"
        g.mode == GateMode.SEALED -> "Paused while the gate is sealed"
        !g.upstreamHealthy -> "Resolver unreachable"
        encrypted -> "Encrypted · DNS-over-${if (ui.settings.protocol == DnsProtocol.DOH) "HTTPS" else "TLS"}"
        else -> "Unencrypted"
    }
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(Dest.WATCHTOWER) }) {
        WidgetHeader("DNS Encryption", value, if (g.running && encrypted && g.upstreamHealthy) c.protectionText else if (g.running) c.premiumText else c.textSecondary) {
            Lamp(g.running && encrypted && g.upstreamHealthy, c.protection)
        }
        if (g.running && active != null) {
            Spacer(Modifier.height(8.dp))
            val parts = buildList {
                add(active.label)
                active.averageLatencyMs?.let { add("$it ms average") }
                if (g.cacheHitRate > 0) add("${(g.cacheHitRate * 100).toInt()}% answered from cache")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun FirewallWidget(ui: FortressUi, onOpen: (Dest) -> Unit) {
    val c = Rg.colors
    val g = ui.guard
    val sealed = g.running && g.mode == GateMode.SEALED
    val value = when {
        !g.running -> "Idle"
        sealed -> "Sealed Gate · ${g.sealedApps} app${if (g.sealedApps == 1) "" else "s"} cut off"
        g.restrictedApps > 0 -> "Watch Gate · ${g.restrictedApps} app${if (g.restrictedApps == 1) "" else "s"} restricted"
        else -> "Watch Gate · no app restrictions"
    }
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(Dest.RAMPARTS) }) {
        WidgetHeader("Firewall Status", value, if (g.running) c.text else c.textSecondary) {
            GateIndicator(down = g.running && (sealed || g.restrictedApps > 0), color = c.privacyText, modifier = Modifier.size(22.dp, 24.dp))
        }
        if (ui.stats.today.firewall + ui.stats.today.refusedConnections > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatCount(ui.stats.today.firewall)} lookups denied · ${formatCount(ui.stats.today.refusedConnections)} connections refused today",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun ActivityWidget(stats: StatsSnapshot, onOpen: (Dest) -> Unit) {
    val c = Rg.colors
    val hour = stats.perMinute.sum()
    val blocked = stats.perMinuteBlocked.sum()
    val pct = if (hour == 0) 0 else blocked * 100 / hour
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(Dest.LOOKOUT) }) {
        WidgetHeader("Network Activity", "${formatCount(hour.toLong())} lookups in the last hour", c.text) {
            Icon(RgIcons.Lookout, null, tint = c.accentText, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(12.dp))
        ActivityBars(
            stats.perMinute, stats.perMinuteBlocked, Modifier.fillMaxWidth().height(48.dp),
            "Lookups per minute for the last hour: $hour total, $blocked blocked",
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c.premium))
            Text("  Blocked $pct%", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(8.dp).background(c.accentText.copy(alpha = 0.35f)))
            Text("  Allowed", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Spacer(Modifier.weight(1f))
            Text("${formatCount(stats.today.total)} today", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        }
    }
}
