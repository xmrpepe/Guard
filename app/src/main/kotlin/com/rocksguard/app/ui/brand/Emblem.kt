package com.rocksguard.app.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import com.rocksguard.app.ui.theme.Rg
import com.rocksguard.app.ui.theme.RgColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The shared geometry of the RocksGuard mark (100 × 120 design space). */
object MarkGeometry {
    const val OUTER = "M10,16 L10,8 L24,8 L24,14 L38,14 L38,8 L62,8 L62,14 L76,14 L76,8 L90,8 L90,58 C90,86 72,104 50,114 C28,104 10,86 10,58 Z"
    const val FIELD = "M17,21 L83,21 L83,58 C83,82 68,97 50,106 C32,97 17,82 17,58 Z"
    const val R = "M33,36 L33,84 L42,84 L42,66 L48,66 L58,84 L68,84 L56.5,64.5 C62,62 66,57 66,50 C66,42 60,36 51,36 Z M42,44 L50,44 C54.5,44 57,46.5 57,50.5 C57,54.5 54.5,58 50,58 L42,58 Z"
    const val SALTIRE = "M17,21 L83,106 M83,21 L17,106"

    fun path(d: String, scale: Float, dx: Float, dy: Float, evenOdd: Boolean = false): Path {
        val p = PathParser().parsePathString(d).toPath()
        p.transform(Matrix().apply { translate(dx, dy); scale(scale, scale) })
        if (evenOdd) p.fillType = PathFillType.EvenOdd
        return p
    }
}

/** Which defensive systems are lit around the shield. */
@Immutable
data class SystemsLit(val filters: Boolean, val dns: Boolean, val firewall: Boolean)

/** The field colour: the theme accent if it is deep enough, otherwise heraldic navy. */
fun shieldField(c: RgColors): Color = if (c.accent.luminance() > 0.3f) Color(0xFF1B2E5A) else c.accent

/**
 * The Highland Command Centre centrepiece. A crenellated shield with an engraved
 * Saltire, inside a Celtic plait ring and three illuminated system arcs.
 *
 * Animation budget: nothing animates while idle. Activation, a blocked threat and a scan
 * each run a short, bounded animation; all are skipped when reduced motion is on.
 */
@Composable
fun FortressEmblem(
    active: Boolean,
    systems: SystemsLit,
    pulseKey: Long,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Rg.colors
    val motion = Rg.motion
    val lit = animateFloatAsState(if (active) 1f else 0f, if (motion.reduced) snap() else tween(700), label = "lit")
    val arcFilters = animateFloatAsState(if (systems.filters) 1f else 0f, if (motion.reduced) snap() else tween(500, 150), label = "a1")
    val arcDns = animateFloatAsState(if (systems.dns) 1f else 0f, if (motion.reduced) snap() else tween(500, 300), label = "a2")
    val arcFw = animateFloatAsState(if (systems.firewall) 1f else 0f, if (motion.reduced) snap() else tween(500, 450), label = "a3")

    // Saltire light: a single bloom when the guard is raised.
    val saltire = remember { Animatable(0f) }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive && !motion.reduced) {
            saltire.snapTo(0f)
            saltire.animateTo(1f, tween(380))
            saltire.animateTo(0f, tween(1100))
        }
        wasActive = active
    }
    // Threat repelled: brief metallic pulse.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulseKey) {
        if (pulseKey > 0 && active && !motion.reduced) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(650))
        }
    }

    // Animated values are read only in the draw phase: frames redraw, nothing recomposes.
    Box(
        modifier.graphicsLayer {
            val s = 1f + 0.03f * pulse.value
            scaleX = s; scaleY = s
        },
    ) {
        Spacer(
            Modifier.fillMaxSize()
                .celticRing({ lit.value }, c) { pulse.value }
                .drawWithCache {
                    val geo = ShieldGeometry(size)
                    onDrawBehind {
                        val l = lit.value
                        drawSystemArcs(c, arcFilters.value * l, arcDns.value * l, arcFw.value * l, motion.lite)
                        drawShield(geo, c, l, saltire.value, motion.lite)
                    }
                },
        )
        if (scanning) RadarSweep(c, Modifier.fillMaxSize())
    }
}

/** Shield paths for one canvas size, built once and reused for every frame. */
private class ShieldGeometry(size: Size) {
    val s = size.minDimension
    val k = s * 0.50f / 106f
    val dx = size.width / 2 - 50f * k
    val dy = size.height / 2 - 61f * k
    val outer = MarkGeometry.path(MarkGeometry.OUTER, k, dx, dy)
    val field = MarkGeometry.path(MarkGeometry.FIELD, k, dx, dy)
    val glyph = MarkGeometry.path(MarkGeometry.R, k, dx, dy, evenOdd = true)
    val saltire = MarkGeometry.path(MarkGeometry.SALTIRE, k, dx, dy)
}

@Composable
private fun RadarSweep(c: RgColors, modifier: Modifier) {
    val motion = Rg.motion
    if (motion.reduced) return
    val t = rememberInfiniteTransition(label = "scan")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Canvas(modifier) {
        val r = size.minDimension * 0.40f
        rotate(angle) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.82f to Color.Transparent, 1f to c.accentText.copy(alpha = 0.38f),
                    center = center,
                ),
                radius = r, center = center,
            )
            drawLine(c.accentText.copy(alpha = 0.7f), center, Offset(center.x + r, center.y), strokeWidth = r * 0.012f, cap = StrokeCap.Round)
        }
    }
}

/** Two-strand Celtic plait (alternating over/under) drawn around the emblem. */
private fun Modifier.celticRing(litOf: () -> Float, c: RgColors, flashOf: () -> Float) = drawWithCache {
    val s = size.minDimension
    val center = Offset(size.width / 2, size.height / 2)
    val r0 = s * 0.455f
    val amp = s * 0.020f
    val w = s * 0.0085f
    val n = 18 // crossings per half-turn
    val segments = 2 * n
    val stepsPerSeg = 18
    fun strand(sign: Float, k: Int): Path {
        val p = Path()
        val a0 = (k - 0.5f) * PI.toFloat() / n
        val a1 = (k + 0.5f) * PI.toFloat() / n
        for (i in 0..stepsPerSeg) {
            val th = a0 + (a1 - a0) * i / stepsPerSeg
            val r = r0 + sign * amp * sin(n * th)
            val x = center.x + r * cos(th - PI.toFloat() / 2)
            val y = center.y + r * sin(th - PI.toFloat() / 2)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        return p
    }
    val strandsA = List(segments) { strand(1f, it) }
    val strandsB = List(segments) { strand(-1f, it) }
    val dim = lerp(c.outlineStrong, c.metalDark, 0.3f)
    val halo = Stroke(w * 2.6f, cap = StrokeCap.Butt)
    val line = Stroke(w, cap = StrokeCap.Round)
    onDrawBehind {
        val lit = litOf()
        val flash = flashOf()
        val col = lerp(dim, c.premium, lit).copy(alpha = 0.55f + 0.35f * lit)
        // Framing lines of the band.
        drawCircle(col.copy(alpha = col.alpha * 0.45f), r0 + amp + w * 1.8f, center, style = Stroke(w * 0.6f))
        drawCircle(col.copy(alpha = col.alpha * 0.45f), r0 - amp - w * 1.8f, center, style = Stroke(w * 0.6f))
        for (k in 0 until segments) {
            val over = if (k % 2 == 0) strandsA[k] else strandsB[k]
            val under = if (k % 2 == 0) strandsB[k] else strandsA[k]
            drawPath(under, col, style = line)
            drawPath(over, c.background, style = halo)
            drawPath(over, col, style = line)
        }
        if (flash > 0f) drawCircle(c.premium.copy(alpha = 0.5f * flash), r0, center, style = Stroke(amp * 2.4f))
    }
}

private fun DrawScope.drawSystemArcs(c: RgColors, filters: Float, dns: Float, fw: Float, lite: Boolean) {
    val s = size.minDimension
    val r = s * 0.395f
    val w = s * 0.014f
    val arcSize = Size(r * 2, r * 2)
    val topLeft = Offset(center.x - r, center.y - r)
    val items = listOf(c.protection to filters, c.accentText to dns, c.privacyText to fw)
    items.forEachIndexed { i, (color, v) ->
        val start = -90f + 6f + i * 120f
        val sweep = 108f
        drawArc(c.outline, start, sweep, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        if (v > 0f) {
            if (!lite) drawArc(color.copy(alpha = 0.22f * v), start, sweep, false, topLeft, arcSize, style = Stroke(w * 3.2f, cap = StrokeCap.Round))
            drawArc(color.copy(alpha = v), start, sweep, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawShield(g: ShieldGeometry, c: RgColors, lit: Float, saltireGlow: Float, lite: Boolean) {
    val s = g.s
    val k = g.k
    val dx = g.dx
    val dy = g.dy
    val outer = g.outer
    val field = g.field
    val glyph = g.glyph
    val saltire = g.saltire

    val grey = lerp(c.metalDark, c.surfaceRaised, 0.4f)
    val m1 = lerp(lerp(c.metalMid, grey, 0.4f), c.metalLight, lit)
    val m2 = lerp(grey, c.metalMid, lit)
    val m3 = lerp(lerp(c.metalDark, c.surfaceSunken, 0.5f), c.metalDark, lit)
    val fieldColor = shieldField(c)
    val f1 = lerp(lerp(fieldColor, c.surfaceRaised, 0.75f), lerp(fieldColor, Color.White, 0.12f), lit)
    val f2 = lerp(lerp(fieldColor, c.surfaceSunken, 0.8f), lerp(fieldColor, Color.Black, 0.35f), lit)

    if (!lite && lit > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(fieldColor.copy(alpha = 0.30f * lit), Color.Transparent), center, s * 0.36f),
            s * 0.36f, center,
        )
    }
    if (!lite) translate(0f, s * 0.012f) { drawPath(outer, Color.Black.copy(alpha = 0.30f)) }
    val top = Offset(dx + 10 * k, dy + 8 * k)
    val bottom = Offset(dx + 90 * k, dy + 114 * k)
    drawPath(outer, Brush.linearGradient(listOf(m1, m2, m3), top, bottom))
    drawPath(field, Brush.linearGradient(listOf(f1, f2), Offset(dx + 17 * k, dy + 21 * k), Offset(dx + 83 * k, dy + 106 * k)))
    clipPath(field) {
        drawPath(saltire, Color.White.copy(alpha = 0.10f + 0.12f * lit + 0.55f * saltireGlow), style = Stroke(9f * k))
        if (saltireGlow > 0f && !lite) {
            drawPath(saltire, c.accentText.copy(alpha = 0.45f * saltireGlow), style = Stroke(18f * k))
        }
    }
    drawPath(field, lerp(c.outlineStrong, c.premium, lit), style = Stroke(1.6f * k))
    drawPath(
        glyph,
        Brush.linearGradient(
            listOf(lerp(m2, Color.White, lit), lerp(m3, c.metalMid, lit)),
            Offset(dx + 33 * k, dy + 36 * k), Offset(dx + 68 * k, dy + 84 * k),
        ),
    )
    if (!lite && lit > 0f) {
        // Brushed-metal sheen across the rim.
        clipPath(outer) {
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.45f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.16f * lit),
                    0.55f to Color.Transparent, 1f to Color.Transparent,
                    start = top, end = bottom,
                ),
            )
        }
    }
}

/** Static small mark (for headers, about, theme previews). */
@Composable
fun MarkIcon(modifier: Modifier = Modifier, colors: RgColors = Rg.colors, lit: Boolean = true) {
    Canvas(modifier) {
        val s = min(size.width, size.height / 1.2f)
        val k = s / 100f * 0.98f
        val dx = (size.width - 100 * k) / 2
        val dy = (size.height - 120 * k) / 2
        val outer = MarkGeometry.path(MarkGeometry.OUTER, k, dx, dy)
        val field = MarkGeometry.path(MarkGeometry.FIELD, k, dx, dy)
        val glyph = MarkGeometry.path(MarkGeometry.R, k, dx, dy, evenOdd = true)
        val saltire = MarkGeometry.path(MarkGeometry.SALTIRE, k, dx, dy)
        val metal = if (lit) Brush.linearGradient(listOf(colors.metalLight, colors.metalMid, colors.metalDark), Offset(dx, dy), Offset(dx + 100 * k, dy + 120 * k))
        else Brush.linearGradient(listOf(colors.outlineStrong, colors.outline))
        drawPath(outer, metal)
        val fc = shieldField(colors)
        drawPath(field, if (lit) Brush.linearGradient(listOf(lerp(fc, Color.White, 0.12f), lerp(fc, Color.Black, 0.35f)), Offset(dx, dy), Offset(dx + 100 * k, dy + 120 * k)) else Brush.linearGradient(listOf(colors.surfaceRaised, colors.surfaceSunken)))
        clipPath(field) { drawPath(saltire, Color.White.copy(alpha = 0.22f), style = Stroke(9f * k)) }
        drawPath(field, colors.premium.copy(alpha = if (lit) 1f else 0.3f), style = Stroke(1.6f * k))
        drawPath(glyph, if (lit) Brush.linearGradient(listOf(Color.White, colors.metalMid)) else Brush.linearGradient(listOf(colors.textTertiary, colors.textTertiary)))
    }
}
