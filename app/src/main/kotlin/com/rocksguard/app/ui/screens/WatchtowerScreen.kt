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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rocksguard.app.data.Settings
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.FilterChipRg
import com.rocksguard.app.ui.components.Lamp
import com.rocksguard.app.ui.components.Note
import com.rocksguard.app.ui.components.Panel
import com.rocksguard.app.ui.components.SaltireButton
import com.rocksguard.app.ui.components.ScreenHeader
import com.rocksguard.app.ui.components.SectionLabel
import com.rocksguard.app.ui.components.Segmented
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.components.ToggleRow
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.vpn.GuardState
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.upstream.DnsProtocol
import com.rocksguard.core.upstream.DnsProvider

@Composable
fun WatchtowerScreen(
    settings: Settings,
    guard: GuardState,
    measured: Map<String, Long?>,
    measuring: Boolean,
    onMeasure: () -> Unit,
    onProvider: (String) -> Unit,
    onFallback: (String?) -> Unit,
    onProtocol: (DnsProtocol) -> Unit,
    onPlaintextFallback: (Boolean) -> Unit,
) {
    val c = Rg.colors
    val current = DnsProvider.byId(settings.providerId) ?: DnsProvider.CATALOGUE.first()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader("DNS manager", "The Watchtower", RgIcons.Watchtower) }
        item {
            val active = guard.running && guard.mode == GateMode.WATCH
            val healthy = active && guard.upstreamHealthy
            Panel(Modifier.fillMaxWidth().padding(top = 12.dp), tint = if (healthy) c.protection else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lamp(healthy, c.protection, Modifier.size(12.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            !guard.running -> "The Watchtower is unmanned"
                            guard.mode == GateMode.SEALED -> "Standing by while the gate is sealed"
                            healthy -> "The Watchtower Is Active"
                            else -> "No resolver is answering"
                        },
                        style = MaterialTheme.typography.headlineSmall, color = c.text,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${current.name} over ${protocolName(settings.protocol)}" + (settings.fallbackId?.let { DnsProvider.byId(it)?.name }?.let { ", falling back to $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                )
                if (guard.running && guard.upstream.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    guard.upstream.forEach { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lamp(h.available, if (h.encrypted) c.protection else c.premium, Modifier.size(8.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(h.label, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(h.averageLatencyMs?.let { "$it ms" }, "${h.successes} ok", if (h.failures > 0) "${h.failures} failed" else null).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = if (h.available) c.textSecondary else c.alertText,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cache: ${guard.cacheEntries} answers held · ${(guard.cacheHitRate * 100).toInt()}% of lookups answered instantly",
                        style = MaterialTheme.typography.labelMedium, color = c.textTertiary,
                    )
                }
            }
            guard.privateDnsStrict?.let {
                Note(
                    "Android's Private DNS is set to $it. In that mode Android sends lookups straight to that server and RocksGuard cannot filter them. Set Private DNS to Off or Automatic in network settings.",
                    c.premiumText, RgIcons.Warning, Modifier.padding(top = 10.dp),
                )
            }
        }
        item {
            SectionLabel("Encryption")
            Segmented(
                listOf(DnsProtocol.DOH to "HTTPS", DnsProtocol.DOT to "TLS", DnsProtocol.PLAIN to "None"),
                settings.protocol, onProtocol,
            )
            Note(
                when (settings.protocol) {
                    DnsProtocol.DOH -> "DNS-over-HTTPS (RFC 8484). Looks like ordinary web traffic and passes most networks. Recommended."
                    DnsProtocol.DOT -> "DNS-over-TLS (RFC 7858) on port 853. Efficient, but some networks block that port."
                    DnsProtocol.PLAIN -> "Unencrypted. Anyone on the network can read and alter your lookups. Use only to diagnose problems."
                },
                if (settings.protocol == DnsProtocol.PLAIN) c.alertText else c.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RESOLVERS", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.weight(1f))
                SaltireButton(if (measuring) "Measuring…" else "Measure speed", onMeasure, filled = false, icon = RgIcons.Radar, enabled = !measuring)
            }
        }
        items(DnsProvider.CATALOGUE, key = { it.id }) { p ->
            ProviderRow(p, p.id == settings.providerId, measured[p.id], measured.containsKey(p.id)) { onProvider(p.id) }
        }
        item {
            SectionLabel("Fallback resolver")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipRg("None", settings.fallbackId == null, { onFallback(null) })
                DnsProvider.CATALOGUE.filter { it.id != settings.providerId }.forEach { p ->
                    FilterChipRg(p.name, settings.fallbackId == p.id, { onFallback(p.id) })
                }
            }
            Note("Used automatically when the main resolver fails three times in a row. It is retried after 30 seconds.", modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Allow unencrypted fallback", "Only if every encrypted resolver is unreachable. Off keeps lookups private at the cost of failing closed.",
                settings.plaintextFallback, onPlaintextFallback, icon = RgIcons.Warning, iconTint = c.premiumText,
                enabled = settings.protocol != DnsProtocol.PLAIN,
            )
        }
    }
}

fun protocolName(p: DnsProtocol) = when (p) {
    DnsProtocol.DOH -> "HTTPS"
    DnsProtocol.DOT -> "TLS"
    DnsProtocol.PLAIN -> "plain DNS"
}

@Composable
private fun ProviderRow(p: DnsProvider, selected: Boolean, ms: Long?, wasMeasured: Boolean, onClick: () -> Unit) {
    val c = Rg.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 64.dp).clip(SmallStone)
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accentText else c.outline, SmallStone)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) c.accentText else c.outlineStrong, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accentText))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(p.summary, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Text(
                listOfNotNull(p.jurisdiction, if (p.blocksMalware) "Malware blocking" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = if (p.blocksMalware) c.protectionText else c.textTertiary,
            )
        }
        if (wasMeasured) {
            Spacer(Modifier.width(8.dp))
            Text(
                ms?.let { "$it ms" } ?: "No reply",
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    ms == null -> c.alertText
                    ms < 60 -> c.protectionText
                    ms < 150 -> c.text
                    else -> c.premiumText
                },
            )
        }
    }
}
