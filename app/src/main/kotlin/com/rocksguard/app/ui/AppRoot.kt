package com.rocksguard.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rocksguard.app.ui.brand.MarkIcon
import com.rocksguard.app.ui.brand.RgIcons
import com.rocksguard.app.ui.components.SmallStone
import com.rocksguard.app.ui.theme.Rg

/** The eight sections of the fortress, each with its plain-English function. */
enum class Dest(val scots: String, val short: String, val function: String, val blurb: String, val icon: ImageVector, val primary: Boolean) {
    FORTRESS("The Fortress", "Fortress", "Dashboard", "Protection status and today's counts", RgIcons.Fortress, true),
    LOOKOUT("The Lookout", "Lookout", "Network monitor", "Every lookup, live and searchable", RgIcons.Lookout, true),
    RAMPARTS("The Ramparts", "Ramparts", "Firewall", "Choose which apps may reach the network", RgIcons.Ramparts, true),
    WATCHTOWER("The Watchtower", "Watchtower", "DNS manager", "Encrypted resolvers and their health", RgIcons.Watchtower, true),
    SHIELD("The Shield", "Shield", "Ad blocker", "What to block and your own rules", RgIcons.Shield, false),
    ARMOURY("The Armoury", "Armoury", "Filter lists", "Blocklists, updates and custom sources", RgIcons.Armoury, false),
    KEEP("The Keep", "Keep", "Privacy centre", "Fortress inspection and privacy report", RgIcons.Keep, false),
    WORKSHOP("The Workshop", "Workshop", "Settings", "Themes, behaviour and about", RgIcons.Workshop, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(initial: Dest = Dest.FORTRESS, content: @Composable (Dest, (Dest) -> Unit) -> Unit) {
    val c = Rg.colors
    var dest by rememberSaveable { mutableStateOf(initial) }
    var showGatehouse by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dest != Dest.FORTRESS) { dest = Dest.FORTRESS }
    val reduced = Rg.motion.reduced

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val wide = maxWidth >= 600.dp
        val body: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(
                targetState = dest,
                transitionSpec = {
                    if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                    else (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 40 }) togetherWith fadeOut(tween(120))
                },
                modifier = m,
                label = "dest",
            ) { d ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // Comfortable reading width on tablets and foldables.
                    Box(Modifier.widthIn(max = if (wide) 840.dp else 10_000.dp).fillMaxSize()) { content(d) { dest = it } }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Rail(dest) { dest = it }
                body(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Top)))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                body(Modifier.weight(1f).fillMaxWidth().statusBarsPadding())
                BottomBar(dest, onSelect = { dest = it }, onMore = { showGatehouse = true })
            }
        }
    }

    if (showGatehouse) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showGatehouse = false }, sheetState = sheet, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("THE GATEHOUSE", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Text("More of the fortress", style = MaterialTheme.typography.headlineSmall, color = c.text)
                Spacer(Modifier.height(12.dp))
                Dest.entries.filter { !it.primary }.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(SmallStone)
                            .clickable(role = Role.Button) { dest = d; showGatehouse = false }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(d.icon, null, tint = c.accentText, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${d.scots} · ${d.function}", style = MaterialTheme.typography.titleMedium, color = c.text)
                            Text(d.blurb, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                        Icon(RgIcons.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(dest: Dest, onSelect: (Dest) -> Unit, onMore: () -> Unit) {
    val c = Rg.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.outline)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup(), horizontalArrangement = Arrangement.SpaceAround) {
            Dest.entries.filter { it.primary }.forEach { d ->
                NavItem(d.icon, d.short, "${d.short}, ${d.function}", dest == d, Modifier.weight(1f)) { onSelect(d) }
            }
            NavItem(RgIcons.More, "More", "More sections", !dest.primary, Modifier.weight(1f), onMore)
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, description: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rg.colors
    Column(
        modifier.heightIn(min = 64.dp)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(52.dp).height(30.dp).clip(SmallStone).background(if (selected) c.accent.copy(alpha = 0.22f) else c.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) c.accentText else c.textSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rail(dest: Dest, onSelect: (Dest) -> Unit) {
    val c = Rg.colors
    Column(
        Modifier.fillMaxHeight().width(112.dp).background(c.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp).selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarkIcon(Modifier.size(36.dp, 44.dp))
        Spacer(Modifier.height(16.dp))
        Dest.entries.forEach { d ->
            NavItem(d.icon, d.short, "${d.short}, ${d.function}", d == dest, Modifier.fillMaxWidth()) { onSelect(d) }
        }
    }
}
