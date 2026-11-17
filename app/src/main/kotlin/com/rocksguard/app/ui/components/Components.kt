package com.rocksguard.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.ui.theme.RgColors
import com.rocksguard.core.brand.ThemeId

/** Stone-block shape: chamfered corners rather than the usual rounded card. */
val StoneShape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp, topEnd = 2.dp, bottomStart = 2.dp)
val SmallStone = CutCornerShape(6.dp)

/** A dressed-stone panel. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Rg.colors
    Column(
        modifier
            .clip(StoneShape)
            .background(c.surface)
            .border(BorderStroke(1.dp, (tint ?: c.outline).copy(alpha = if (tint != null) 0.55f else 1f)), StoneShape)
            .padding(padding),
        content = content,
    )
}

/**
 * Section header: functional label above the Scottish name, then an engraved rule.
 * e.g. FIREWALL / The Ramparts.
 */
@Composable
fun ScreenHeader(function: String, title: String, icon: ImageVector, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = Rg.colors
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(SmallStone).background(c.surfaceRaised).border(1.dp, c.outline, SmallStone),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = c.accentText, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(function.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Text(title, style = MaterialTheme.typography.headlineMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        EngravedRule()
        if (Rg.theme == ThemeId.SCOTTISH_HERITAGE) {
            Spacer(Modifier.height(6.dp))
            TartanBand(Modifier.fillMaxWidth().height(6.dp))
        }
    }
}

/** A thin engraved line with a lozenge at its centre, like a carved lintel. */
@Composable
fun EngravedRule(modifier: Modifier = Modifier, color: Color = Rg.colors.outlineStrong) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val y = size.height / 2
        val cx = size.width / 2
        val d = size.height / 2
        drawLine(color.copy(alpha = 0.6f), Offset(0f, y), Offset(cx - d * 2.2f, y), strokeWidth = 1.dp.toPx())
        drawLine(color.copy(alpha = 0.6f), Offset(cx + d * 2.2f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        val p = Path().apply { moveTo(cx, y - d); lineTo(cx + d * 1.6f, y); lineTo(cx, y + d); lineTo(cx - d * 1.6f, y); close() }
        drawPath(p, color, style = Stroke(1.dp.toPx()))
        drawCircle(color, d * 0.35f, Offset(cx, y))
    }
}

/** Original RocksGuard sett woven procedurally (warp and weft at half opacity). */
@Composable
fun TartanBand(modifier: Modifier = Modifier, colors: RgColors = Rg.colors) {
    val sett = colors.tartan.ifEmpty { listOf(colors.accent, colors.surfaceRaised, colors.protection, colors.premium, colors.background) }
    Box(
        modifier.clip(RoundedCornerShape(1.dp)).drawWithCache {
            // Thread count (colour index, width) of the RocksGuard sett, mirrored.
            val half = listOf(0 to 8f, 4 to 1.5f, 0 to 2f, 4 to 1.5f, 0 to 8f, 1 to 10f, 2 to 1.5f, 1 to 10f, 3 to 1.2f)
            val pattern = half + half.reversed()
            val unit = size.height / 6f
            onDrawBehind {
                drawRect(sett[4 % sett.size])
                var x = 0f
                var i = 0
                while (x < size.width) {
                    val (ci, w) = pattern[i % pattern.size]
                    drawRect(sett[ci % sett.size].copy(alpha = 0.85f), Offset(x, 0f), Size(w * unit, size.height))
                    x += w * unit; i++
                }
                var y = 0f
                i = 3
                while (y < size.height) {
                    val (ci, w) = pattern[i % pattern.size]
                    drawRect(sett[ci % sett.size].copy(alpha = 0.45f), Offset(0f, y), Size(size.width, w * unit * 0.5f))
                    y += w * unit * 0.5f; i++
                }
            }
        },
    )
}

/** Portcullis gate. [closed] 0 = raised, 1 = fully down. */
@Composable
fun Portcullis(closed: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.07f).coerceAtLeast(1.5f)
        val arch = Path().apply {
            moveTo(w * 0.08f, h); lineTo(w * 0.08f, h * 0.35f)
            cubicTo(w * 0.08f, h * 0.02f, w * 0.92f, h * 0.02f, w * 0.92f, h * 0.35f)
            lineTo(w * 0.92f, h)
        }
        drawPath(arch, color.copy(alpha = 0.5f), style = Stroke(stroke, cap = StrokeCap.Round))
        val bottom = h * 0.2f + (h * 0.78f) * closed
        val bars = listOf(0.3f, 0.5f, 0.7f)
        for (b in bars) {
            val top = if (b == 0.5f) h * 0.12f else h * 0.18f
            drawLine(color, Offset(w * b, top), Offset(w * b, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (f in listOf(0.45f, 0.75f)) {
            val y = h * 0.18f + (bottom - h * 0.18f) * f
            if (y > h * 0.25f) drawLine(color, Offset(w * 0.22f, y), Offset(w * 0.78f, y), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        }
    }
}

/** Animated portcullis that drops when [down] becomes true. */
@Composable
fun GateIndicator(down: Boolean, color: Color, modifier: Modifier = Modifier) {
    val reduced = Rg.motion.reduced
    val f by animateFloatAsState(if (down) 1f else 0f, if (reduced) snap() else tween(650), label = "gate")
    Portcullis(f, color, modifier)
}

/** Status lamp. */
@Composable
fun Lamp(on: Boolean, color: Color, modifier: Modifier = Modifier) {
    val c = Rg.colors
    Box(
        modifier.size(10.dp).clip(CircleShape)
            .background(if (on) color else c.outlineStrong.copy(alpha = 0.5f))
            .border(1.dp, if (on) color.copy(alpha = 0.6f) else c.outline, CircleShape),
    )
}

/** Toggle row with a full-row touch target and proper switch semantics. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Rg.colors.accentText,
    enabled: Boolean = true,
) {
    val c = Rg.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) iconTint else c.textTertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.text else c.textTertiary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, checkedBorderColor = c.accent,
                uncheckedTrackColor = c.surfaceSunken, uncheckedThumbColor = c.textTertiary, uncheckedBorderColor = c.outlineStrong,
            ),
        )
    }
}

/** A row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Rg.colors
    Row(
        modifier.fillMaxWidth().clip(SmallStone).background(c.surfaceSunken).border(1.dp, c.outline, SmallStone).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((value, label) in options) {
            val sel = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(SmallStone)
                    .background(if (sel) c.accent else Color.Transparent)
                    .selectable(sel, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, color = if (sel) c.onAccent else c.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Primary action in Saltire blue (or the theme accent). */
@Composable
fun SaltireButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = true, enabled: Boolean = true) {
    val c = Rg.colors
    val bg = if (filled) c.accent else Color.Transparent
    val fg = if (filled) c.onAccent else c.accentText
    Row(
        modifier.heightIn(min = 48.dp).clip(SmallStone).background(if (enabled) bg else c.surfaceRaised)
            .then(if (filled) Modifier else Modifier.border(1.dp, c.outlineStrong, SmallStone))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else c.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = if (enabled) fg else c.textTertiary)
    }
}

/** Compact text chip used for filters. */
@Composable
fun FilterChipRg(label: String, selected: Boolean, onClick: () -> Unit, color: Color = Rg.colors.accent) {
    val c = Rg.colors
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else c.surface)
            .border(1.dp, if (selected) color else c.outline, RoundedCornerShape(18.dp))
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.text else c.textSecondary)
    }
}

/** Bar sparkline (total vs blocked) for the last hour. */
@Composable
fun ActivityBars(total: IntArray, blocked: IntArray, modifier: Modifier = Modifier, description: String) {
    val c = Rg.colors
    Canvas(modifier.semantics { contentDescription = description }) {
        val max = (total.maxOrNull() ?: 0).coerceAtLeast(1)
        val n = total.size
        val gap = size.width / n * 0.25f
        val bw = size.width / n - gap
        for (i in 0 until n) {
            val x = i * (bw + gap)
            val th = size.height * total[i] / max
            val bh = size.height * blocked[i] / max
            if (total[i] == 0) {
                drawRect(c.outline, Offset(x, size.height - 1.dp.toPx()), Size(bw, 1.dp.toPx()))
                continue
            }
            drawRect(c.accentText.copy(alpha = 0.35f), Offset(x, size.height - th), Size(bw, th))
            if (bh > 0) drawRect(c.premium, Offset(x, size.height - bh), Size(bw, bh))
        }
    }
}

/** Horizontal proportion bar. */
@Composable
fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val c = Rg.colors
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(c.surfaceSunken)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Rg.colors.textTertiary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun Note(text: String, color: Color = Rg.colors.textSecondary, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Formats a count compactly but exactly for small numbers: 950, 12,431, 1.2M. */
fun formatCount(v: Long): String = when {
    v < 1_000_000 -> java.text.NumberFormat.getIntegerInstance().format(v)
    else -> String.format(java.util.Locale.getDefault(), "%.1fM", v / 1_000_000.0)
}

@Composable
fun rememberCount(v: Long) = remember(v) { formatCount(v) }
