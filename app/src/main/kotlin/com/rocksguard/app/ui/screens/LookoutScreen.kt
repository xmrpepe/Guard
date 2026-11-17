package com.rocksguard.app.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.ActivityEntry
import com.rocksguard.app.ui.Dest
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.EngravedRule
import com.rocksguard.app.ui.components.FilterChipRg
import com.rocksguard.app.ui.components.Note
import com.rocksguard.app.ui.components.SaltireButton
import com.rocksguard.app.ui.components.ScreenHeader
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.ui.theme.RgColors
import com.rocksguard.core.dns.DnsType
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.Verdict
import java.text.DateFormat
import java.util.Date

enum class LookoutFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), ALLOWED("Allowed"), THREATS("Threats"), FIREWALL("Firewall") }

/** Plain-English account of a verdict, used in the activity details. */
fun explain(e: ActivityEntry, appLabel: String?): Pair<String, String> {
    val who = appLabel?.let { " $it asked for it." } ?: ""
    fun answered() = "RocksGuard answered on the device, so nothing was contacted.$who"
    return when (val v = e.verdict) {
        is Verdict.BlockedByList -> {
            val cat = categoryName(v.category)
            val scope = if (v.viaSubdomainRule) "the rule for ${v.matched}, which covers all of its subdomains," else "an exact rule"
            "Blocked · $cat" to "${e.name} matched $scope in ${v.source?.name ?: "a filter list"}. ${answered()}"
        }
        is Verdict.BlockedCname -> {
            val src = (v.inner as? Verdict.BlockedByList)?.source?.name ?: "your rules"
            "Blocked · Disguised tracker" to "${e.name} looked like a first-party address, but its DNS record pointed to ${v.cname}, which is listed in $src. This trick is called CNAME cloaking.$who"
        }
        is Verdict.BlockedByUser -> "Blocked · Your rule" to "You blocked ${v.rule} and everything beneath it. ${answered()}"
        is Verdict.BlockedByFirewall -> "Blocked · The Ramparts" to "${appLabel ?: "This app"} is denied network access on this network by your firewall rules."
        is Verdict.BlockedCanary -> "Blocked · Bypass canary" to "Browsers look up this name to decide whether to switch to their own encrypted DNS. Answering ‘does not exist’ keeps them behind RocksGuard's filters."
        is Verdict.AllowedByUser -> "Allowed · Your exception" to "You allowed ${v.rule}, so lists cannot block it.${resolution(e)}"
        is Verdict.AllowedByListException -> "Allowed · List exception" to "${v.source?.name ?: "A filter list"} marks ${v.rule} as safe to keep sites working.${resolution(e)}"
        Verdict.Allowed -> (if (e.failed) "Resolver failed" else "Allowed") to
            (if (e.failed) "No resolver answered in time, so the app received a temporary failure and will retry." else "Not on any enabled list.${resolution(e)}")
    }
}

private fun resolution(e: ActivityEntry): String = when {
    e.cached -> " Answered from RocksGuard's cache in ${e.latencyMs} ms."
    e.transport != null -> " Resolved by ${e.transport} in ${e.latencyMs} ms${if (e.encrypted) ", encrypted" else ", unencrypted"}."
    else -> ""
}

fun categoryName(c: Category) = when (c) {
    Category.ADS -> "Advert"
    Category.TRACKERS -> "Tracker"
    Category.THREATS -> "Threat"
    Category.BYPASS -> "Filter bypass"
    Category.NONE -> "Rule"
}

private fun verdictColor(v: Verdict, c: RgColors): Color = when {
    v is Verdict.BlockedByFirewall -> c.privacyText
    v.blocked && v.statCategory == Category.THREATS -> c.alertText
    v.blocked && v.statCategory == Category.TRACKERS -> c.privacyText
    v.blocked -> c.premiumText
    else -> c.protectionText
}

private fun shortTag(e: ActivityEntry): String = when (val v = e.verdict) {
    is Verdict.BlockedByList -> categoryName(v.category)
    is Verdict.BlockedCname -> "Cloaked tracker"
    is Verdict.BlockedByUser -> "Your rule"
    is Verdict.BlockedByFirewall -> "Firewall"
    is Verdict.BlockedCanary -> "Canary"
    else -> when {
        e.failed -> "Failed"
        e.cached -> "Cache · ${e.latencyMs} ms"
        else -> "${e.latencyMs} ms"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookoutScreen(
    entries: List<ActivityEntry>,
    recording: Boolean,
    paused: Boolean,
    appLabel: (Int) -> String?,
    onPause: (Boolean) -> Unit,
    onClear: () -> Unit,
    onAllow: (String) -> Unit,
    onBlock: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpen: (Dest) -> Unit,
) {
    val c = Rg.colors
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LookoutFilter.ALL) }
    var selected by remember { mutableStateOf<ActivityEntry?>(null) }
    val q = query.trim().lowercase()
    val shown = remember(entries, q, filter) {
        entries.filter { e ->
            val matchesFilter = when (filter) {
                LookoutFilter.ALL -> true
                LookoutFilter.BLOCKED -> e.verdict.blocked
                LookoutFilter.ALLOWED -> !e.verdict.blocked
                LookoutFilter.THREATS -> e.verdict.statCategory == Category.THREATS
                LookoutFilter.FIREWALL -> e.verdict is Verdict.BlockedByFirewall
            }
            matchesFilter && (q.isEmpty() || e.name.contains(q) || (appLabel(e.uid)?.lowercase()?.contains(q) == true))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenHeader("Network monitor", "The Lookout", RgIcons.Lookout) {
                IconButton({ onPause(!paused) }, modifier = Modifier.semantics { contentDescription = if (paused) "Resume live updates" else "Pause live updates" }) {
                    Icon(if (paused) RgIcons.Play else RgIcons.Pause, null, tint = c.accentText)
                }
                IconButton(onClear, modifier = Modifier.semantics { contentDescription = "Clear activity" }) {
                    Icon(RgIcons.Trash, null, tint = c.textSecondary)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).clip(SmallStone).background(c.surface).border(1.dp, c.outline, SmallStone).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RgIcons.Search, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search domains or apps", style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accentText),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search activity" },
                    )
                }
                if (query.isNotEmpty()) IconButton({ query = "" }, modifier = Modifier.semantics { contentDescription = "Clear search" }) {
                    Icon(RgIcons.Cross, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookoutFilter.entries.forEach { f -> FilterChipRg(f.label, filter == f, { filter = f }) }
            }
        }
        if (!recording) item {
            Note("Activity recording is off in the Workshop, so new lookups are counted but not listed.", c.premiumText, RgIcons.Info)
        }
        if (shown.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(RgIcons.Lookout, null, tint = c.textTertiary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (entries.isEmpty()) "All quiet on the walls" else "Nothing matches", style = MaterialTheme.typography.headlineSmall, color = c.textSecondary)
                Text(
                    if (entries.isEmpty()) "Lookups appear here as apps make them. The log lives only in memory and never leaves this device." else "Try another search or filter.",
                    style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                )
                if (entries.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SaltireButton("Go to the Fortress", { onOpen(Dest.FORTRESS) }, filled = false)
                }
            }
        }
        items(shown, key = { it.id }, contentType = { 0 }) { e -> EntryRow(e, appLabel(e.uid)) { selected = e } }
    }

    selected?.let { e ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val label = appLabel(e.uid)
        val (title, body) = explain(e, label)
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = sheet, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = verdictColor(e.verdict, c))
                Text(e.name, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), color = c.text)
                Spacer(Modifier.height(10.dp))
                EngravedRule()
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Text(
                    listOfNotNull(
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.time)),
                        DnsType.name(e.type) + " record",
                        label,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = c.textTertiary,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (e.verdict.blocked && e.verdict !is Verdict.BlockedByFirewall) {
                        SaltireButton("Always allow", { onAllow(e.name); selected = null }, icon = RgIcons.Check)
                    } else if (!e.verdict.blocked) {
                        SaltireButton("Always block", { onBlock(e.name); selected = null }, icon = RgIcons.Shield)
                    }
                    SaltireButton("Copy", { onCopy(e.name) }, filled = false, icon = RgIcons.Copy)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: ActivityEntry, app: String?, onClick: () -> Unit) {
    val c = Rg.colors
    val color = verdictColor(e.verdict, c)
    val time = remember(e.time) { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.time)) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${if (e.verdict.blocked) "Blocked" else "Allowed"} ${e.name}${app?.let { ", by $it" } ?: ""}, ${shortTag(e)}"
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Coloured "arrow slit" marks the verdict at a glance; the text says it too.
        Box(Modifier.width(4.dp).height(38.dp).clip(SmallStone).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(app, shortTag(e)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (e.verdict.blocked) color else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            Text(if (e.verdict.blocked) "BLOCKED" else DnsType.name(e.type), style = MaterialTheme.typography.labelSmall, color = if (e.verdict.blocked) color else c.textTertiary)
        }
    }
}
