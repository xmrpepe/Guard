package com.rocksguard.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.InstalledApp
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.FilterChipRg
import com.rocksguard.app.ui.components.GateIndicator
import com.rocksguard.app.ui.components.Note
import com.rocksguard.app.ui.components.Panel
import com.rocksguard.app.ui.components.ScreenHeader
import com.rocksguard.app.ui.components.SectionLabel
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.components.StoneShape
import com.rocksguard.app.ui.components.ToggleRow
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.core.engine.AppRule
import com.rocksguard.core.engine.GateMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) { ALL("All apps"), RESTRICTED("Restricted"), USER("Installed"), SYSTEM("System") }

@Composable
fun RampartsScreen(
    apps: List<InstalledApp>?,
    rules: Map<String, AppRule>,
    gateMode: GateMode,
    perAppDnsSupported: Boolean,
    iconLoader: (String, Int) -> ImageBitmap?,
    onGateMode: (GateMode) -> Unit,
    onRule: (String, (AppRule) -> AppRule) -> Unit,
) {
    val c = Rg.colors
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val restricted = rules.values.count { it.hasBlock && !it.bypass }
    val q = query.trim().lowercase()
    val shown = remember(apps, rules, q, filter) {
        (apps ?: emptyList()).filter { a ->
            val r = rules[a.packageName]
            val f = when (filter) {
                AppFilter.ALL -> true
                AppFilter.RESTRICTED -> r != null && (r.hasBlock || r.bypass)
                AppFilter.USER -> !a.isSystem
                AppFilter.SYSTEM -> a.isSystem
            }
            f && (q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.contains(q))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenHeader("Firewall", "The Ramparts", RgIcons.Ramparts) {
                GateIndicator(down = restricted > 0 || gateMode == GateMode.SEALED, color = c.privacyText, modifier = Modifier.size(28.dp, 32.dp))
            }
        }
        item {
            SectionLabel("The gate")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GateCard(
                    "Watch Gate", "Recommended",
                    "Every app is filtered for adverts, trackers and threats. Restricted apps have their lookups refused.",
                    gateMode == GateMode.WATCH, Modifier.weight(1f),
                ) { onGateMode(GateMode.WATCH) }
                GateCard(
                    "Sealed Gate", "Strict",
                    "Restricted apps lose all network access, even by IP address. Ad blocking pauses for other apps while sealed.",
                    gateMode == GateMode.SEALED, Modifier.weight(1f),
                ) { onGateMode(GateMode.SEALED) }
            }
            if (!perAppDnsSupported && gateMode == GateMode.WATCH) {
                Note("This version of Android cannot tell which app made a lookup, so Watch Gate restrictions need Android 10 or later. Sealed Gate works on every version.", c.premiumText, RgIcons.Warning, Modifier.padding(top = 8.dp))
            }
            if (gateMode == GateMode.WATCH) {
                Note("Watch Gate stops apps that look up names, which is nearly all of them. An app that connects straight to a hard-coded address needs the Sealed Gate.", c.textTertiary, RgIcons.Info, Modifier.padding(top = 8.dp))
            }
        }
        item {
            SectionLabel("Apps · $restricted restricted")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(SmallStone).background(c.surface).border(1.dp, c.outline, SmallStone).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RgIcons.Search, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
                    BasicTextField(
                        query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search apps" },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.entries.forEach { f -> FilterChipRg(f.label, filter == f, { filter = f }, c.privacy) }
            }
        }
        if (apps == null) item {
            Text("Mustering the garrison…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(shown, key = { it.packageName }, contentType = { 0 }) { a ->
            AppRow(a, rules[a.packageName], expanded == a.packageName, iconLoader, { expanded = if (expanded == a.packageName) null else a.packageName }) { t ->
                onRule(a.packageName, t)
            }
        }
    }
}

@Composable
private fun GateCard(title: String, tag: String, body: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rg.colors
    Column(
        modifier.clip(StoneShape).background(if (selected) c.privacy.copy(alpha = 0.14f) else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.privacyText else c.outline, StoneShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GateIndicator(down = title.startsWith("Sealed"), color = if (selected) c.privacyText else c.textTertiary, modifier = Modifier.size(18.dp, 20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (selected) c.privacyText else c.textTertiary)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    rule: AppRule?,
    expanded: Boolean,
    iconLoader: (String, Int) -> ImageBitmap?,
    onExpand: () -> Unit,
    onRule: ((AppRule) -> AppRule) -> Unit,
) {
    val c = Rg.colors
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.packageName) { value = withContext(Dispatchers.IO) { iconLoader(app.packageName, px) } }
    val r = rule ?: AppRule(app.packageName, app.uid)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onExpand)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val bmp = icon
                if (bmp != null) Image(bmp, null, Modifier.size(40.dp))
                else Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        r.bypass -> "Bypasses RocksGuard"
                        r.blockOnWifi && r.blockOnMobile -> "Blocked on all networks"
                        r.blockOnWifi -> "Blocked on Wi‑Fi"
                        r.blockOnMobile -> "Blocked on mobile data"
                        !app.usesInternet -> "Does not request internet access"
                        else -> app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.hasBlock || r.bypass) c.privacyText else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            NetToggle(RgIcons.Wifi, "Wi‑Fi", app.label, r.blockOnWifi, !r.bypass) { b -> onRule { it.copy(blockOnWifi = b) } }
            Spacer(Modifier.width(6.dp))
            NetToggle(RgIcons.Mobile, "Mobile data", app.label, r.blockOnMobile, !r.bypass) { b -> onRule { it.copy(blockOnMobile = b) } }
        }
        if (expanded) {
            Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleRow(
                    "Bypass RocksGuard",
                    "For apps that refuse to work behind a VPN, such as some banking or casting apps. Their lookups are not filtered.",
                    r.bypass, { b -> onRule { it.copy(bypass = b) } }, icon = RgIcons.Bypass, iconTint = c.premiumText,
                )
                Text("${app.packageName} · uid ${app.uid}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** Blocked state is shown by colour, a strike-through glyph and the accessibility state. */
@Composable
private fun NetToggle(icon: ImageVector, network: String, app: String, blocked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rg.colors
    val color: Color = if (blocked) c.alertText else c.protectionText
    Box(
        Modifier.size(44.dp).clip(SmallStone)
            .background(if (blocked) c.alert.copy(alpha = 0.16f) else c.surface)
            .border(1.dp, if (blocked) c.alertText else c.outline, SmallStone)
            .toggleable(blocked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "$network for $app"; stateDescription = if (blocked) "Blocked" else "Allowed" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (!enabled) c.textTertiary else if (blocked) color.copy(alpha = 0.45f) else color, modifier = Modifier.size(20.dp))
        // A single diagonal bar marks "blocked", like a barred gate.
        if (blocked) androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.9f), androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f), strokeWidth = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
