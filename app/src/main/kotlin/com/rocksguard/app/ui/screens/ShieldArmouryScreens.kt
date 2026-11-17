package com.rocksguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.CompileState
import com.rocksguard.app.data.Counters
import com.rocksguard.app.data.ListState
import com.rocksguard.app.data.Settings
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.EngravedRule
import com.rocksguard.app.ui.components.Note
import com.rocksguard.app.ui.components.Panel
import com.rocksguard.app.ui.components.SaltireButton
import com.rocksguard.app.ui.components.ScreenHeader
import com.rocksguard.app.ui.components.SectionLabel
import com.rocksguard.app.ui.components.Segmented
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.components.StoneShape
import com.rocksguard.app.ui.components.ToggleRow
import com.rocksguard.app.ui.components.formatCount
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.core.dns.BlockResponse
import com.rocksguard.core.filter.Category
import com.rocksguard.core.filter.FilterParser
import java.text.DateFormat
import java.util.Date

// ===================================================================== The Shield

@Composable
fun ShieldScreen(
    settings: Settings,
    today: Counters,
    onCategory: (Category, Boolean) -> Unit,
    onCname: (Boolean) -> Unit,
    onCanary: (Boolean) -> Unit,
    onBlockResponse: (BlockResponse) -> Unit,
    onAddRule: (String, Boolean) -> String?,
    onRemoveRule: (String, Boolean) -> Unit,
) {
    val c = Rg.colors
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader("Ad blocker", "The Shield", RgIcons.Shield) }
        item {
            SectionLabel("Raise the shield against")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Banner("Adverts", "Ad networks and ad servers", today.adverts, c.premiumText, Category.ADS in settings.categories) { onCategory(Category.ADS, it) }
                Banner("Trackers", "Analytics, attribution and data brokers", today.trackers, c.privacyText, Category.TRACKERS in settings.categories) { onCategory(Category.TRACKERS, it) }
                Banner("Threats", "Malware, phishing and scam domains", today.threats, c.alertText, Category.THREATS in settings.categories) { onCategory(Category.THREATS, it) }
                Banner("Filter bypass", "Hidden DNS, VPN and proxy services apps use to escape filtering", today.bypass, c.accentText, Category.BYPASS in settings.categories) { onCategory(Category.BYPASS, it) }
            }
        }
        item {
            SectionLabel("Advanced protection")
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleRow(
                    "Unmask disguised trackers",
                    "Checks where each name really points (CNAME uncloaking), catching trackers hidden behind a site's own address. ${formatCount(today.cname)} caught today.",
                    settings.cnameUncloaking, onCname, icon = RgIcons.Lookout,
                )
                ToggleRow(
                    "Keep browsers behind the shield",
                    "Answers the canary domain browsers check before switching to their own encrypted DNS.",
                    settings.bypassCanary, onCanary, icon = RgIcons.Globe,
                )
            }
        }
        item {
            SectionLabel("Blocked names receive")
            Segmented(
                listOf(BlockResponse.NULL_IP to "0.0.0.0", BlockResponse.NXDOMAIN to "Not found", BlockResponse.REFUSED to "Refused"),
                settings.blockResponse, onBlockResponse,
            )
            Note(
                when (settings.blockResponse) {
                    BlockResponse.NULL_IP -> "The unspecified address. Apps fail instantly without retrying. Recommended."
                    BlockResponse.NXDOMAIN -> "‘Domain does not exist’. Some apps retry more often with this answer."
                    BlockResponse.REFUSED -> "‘Refused’. Some apps then try another resolver, which RocksGuard also guards."
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            SectionLabel("Your rules")
            Panel(Modifier.fillMaxWidth()) {
                Text("Add a domain. It covers every subdomain too.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(SmallStone).background(c.surfaceSunken).border(1.dp, if (error != null) c.alertText else c.outline, SmallStone).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.isEmpty()) Text("example.com", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = c.textTertiary)
                    BasicTextField(
                        input, { input = it; error = null }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = c.text),
                        cursorBrush = SolidColor(c.accentText),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Domain for a rule" },
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.alertText, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SaltireButton("Block", { error = onAddRule(input, true); if (error == null) input = "" }, icon = RgIcons.Shield)
                    SaltireButton("Allow", { error = onAddRule(input, false); if (error == null) input = "" }, filled = false, icon = RgIcons.Check)
                }
            }
        }
        if (settings.userBlocked.isNotEmpty()) {
            item { SectionLabel("Always blocked · ${settings.userBlocked.size}") }
            items(settings.userBlocked.sorted(), key = { "b-$it" }) { RuleRow(it, c.alertText) { onRemoveRule(it, true) } }
        }
        if (settings.userAllowed.isNotEmpty()) {
            item { SectionLabel("Always allowed · ${settings.userAllowed.size}") }
            items(settings.userAllowed.sorted(), key = { "a-$it" }) { RuleRow(it, c.protectionText) { onRemoveRule(it, false) } }
        }
    }
}

/** Category banner: heraldic pennant edge, today's count, and a switch. */
@Composable
private fun Banner(title: String, subtitle: String, count: Long, color: Color, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rg.colors
    Row(
        Modifier.fillMaxWidth().clip(StoneShape).background(c.surface).border(1.dp, if (on) color.copy(alpha = 0.6f) else c.outline, StoneShape)
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "Block ${title.lowercase()}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(76.dp).background(if (on) color else c.outline))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Text("${formatCount(count)} today", style = MaterialTheme.typography.labelMedium, color = if (on) color else c.textTertiary)
        }
        Switch(
            on, null, modifier = Modifier.padding(end = 14.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surfaceSunken, uncheckedBorderColor = c.outlineStrong, uncheckedThumbColor = c.textTertiary),
        )
    }
}

@Composable
private fun RuleRow(domain: String, color: Color, onRemove: () -> Unit) {
    val c = Rg.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(SmallStone).background(color))
        Spacer(Modifier.width(12.dp))
        Text(domain, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.weight(1f))
        IconButton(onRemove, modifier = Modifier.semantics { contentDescription = "Remove rule for $domain" }) {
            Icon(RgIcons.Cross, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Validates a user rule; returns the normalised domain or null. */
fun normaliseRule(input: String): String? = FilterParser.normalise(input.trim().removePrefix("*.").substringAfter("://").substringBefore('/'))

// ===================================================================== The Armoury

@Composable
fun ArmouryScreen(
    lists: List<ListState>,
    compile: CompileState,
    onToggle: (String, Boolean) -> Unit,
    onUpdate: () -> Unit,
    onAddCustom: (String, String, Category) -> String?,
    onRemoveCustom: (String) -> Unit,
) {
    val c = Rg.colors
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(Category.ADS) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = compile.compiling || compile.updating
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader("Filter lists", "The Armoury", RgIcons.Armoury) }
        item {
            Panel(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(formatCount(compile.totalDomains.toLong()), style = MaterialTheme.typography.displaySmall, color = c.text)
                Text("domains guarded across ${lists.count { it.enabled }} lists", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (compile.lastCompiledAt > 0) {
                    Text(
                        "Compiled ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(compile.lastCompiledAt))} · about ${formatCount(compile.totalDomains * 12L / 1024)} KB in memory",
                        style = MaterialTheme.typography.labelMedium, color = c.textTertiary,
                    )
                }
                compile.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.alertText) }
                Spacer(Modifier.height(12.dp))
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accentText, trackColor = c.surfaceSunken)
                    Text(if (compile.updating) "Fetching lists…" else "Forging the rule set…", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                } else {
                    SaltireButton("Update all lists", onUpdate, icon = RgIcons.Refresh)
                }
            }
            Note("Lists update about once a day when the battery is not low (Wi‑Fi only by default). Unchanged lists are not downloaded again.", modifier = Modifier.padding(top = 8.dp))
        }
        for (cat in listOf(Category.ADS, Category.TRACKERS, Category.THREATS, Category.BYPASS)) {
            val group = lists.filter { it.list.category == cat }
            if (group.isEmpty()) continue
            item { SectionLabel(when (cat) { Category.ADS -> "Adverts"; Category.TRACKERS -> "Trackers"; Category.THREATS -> "Threats"; else -> "Filter bypass" }) }
            items(group, key = { it.list.id }) { s -> ListRow(s, onToggle, onRemoveCustom) }
        }
        item {
            SectionLabel("Forge a custom list")
            Panel(Modifier.fillMaxWidth()) {
                Field(name, { name = it }, "Name (optional)")
                Spacer(Modifier.height(8.dp))
                Field(url, { url = it; error = null }, "https://example.com/list.txt", mono = true)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(Category.ADS to "Adverts", Category.TRACKERS to "Trackers", Category.THREATS to "Threats"), category, { category = it })
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.alertText, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                SaltireButton("Add list", {
                    error = onAddCustom(name, url, category)
                    if (error == null) { name = ""; url = "" }
                }, icon = RgIcons.Plus)
                Note("Hosts files, plain domain lists and Adblock-style DNS lists are understood. HTTPS only.", modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, mono: Boolean = false) {
    val c = Rg.colors
    val style = MaterialTheme.typography.bodyLarge.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(SmallStone).background(c.surfaceSunken).border(1.dp, c.outline, SmallStone).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, style = style, color = c.textTertiary)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = style.copy(color = c.text), cursorBrush = SolidColor(c.accentText),
            keyboardOptions = if (mono) KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        )
    }
}

@Composable
private fun ListRow(s: ListState, onToggle: (String, Boolean) -> Unit, onRemove: (String) -> Unit) {
    val c = Rg.colors
    val l = s.list
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ToggleRow(
            l.name,
            buildString {
                append(l.description)
                append('\n')
                append(
                    listOfNotNull(
                        if (s.meta.rules > 0) "${formatCount(s.meta.rules.toLong())} rules" else null,
                        when {
                            l.url == null -> "Built in"
                            s.meta.updatedAt > 0 -> "Updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.meta.updatedAt))
                            s.enabled -> "Not downloaded yet"
                            else -> null
                        },
                        l.licence.takeIf { l.url != null },
                    ).joinToString(" · "),
                )
            },
            s.enabled, { onToggle(l.id, it) },
        )
        s.meta.error?.let { Note("Last update failed: $it", c.alertText, RgIcons.Warning) }
        if (l.custom) SaltireButton("Remove list", { onRemove(l.id) }, filled = false, icon = RgIcons.Trash)
        EngravedRule(color = c.outline)
    }
}
