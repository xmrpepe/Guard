package com.rocksguard.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings as SysSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rocksguard.app.R
import com.rocksguard.app.data.Brightness
import com.rocksguard.app.data.EffectsPref
import com.rocksguard.app.data.MotionPref
import com.rocksguard.app.data.Settings
import com.rocksguard.core.brand.Palette
import com.rocksguard.core.brand.Palettes
import com.rocksguard.core.brand.ThemeId
import kotlinx.coroutines.delay
import java.util.Calendar

/** Highland Shield colour tokens as Compose colours. */
@Immutable
data class RgColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val protection: Color,
    val protectionText: Color,
    val privacy: Color,
    val privacyText: Color,
    val premium: Color,
    val premiumText: Color,
    val alert: Color,
    val alertText: Color,
    val metalLight: Color,
    val metalMid: Color,
    val metalDark: Color,
    val tartan: List<Color>,
) {
    companion object {
        fun from(p: Palette) = RgColors(
            p.isDark, Color(p.background), Color(p.surface), Color(p.surfaceRaised), Color(p.surfaceSunken),
            Color(p.outline), Color(p.outlineStrong), Color(p.text), Color(p.textSecondary), Color(p.textTertiary),
            Color(p.accent), Color(p.onAccent), Color(p.accentText), Color(p.protection), Color(p.protectionText),
            Color(p.privacy), Color(p.privacyText), Color(p.premium), Color(p.premiumText), Color(p.alert), Color(p.alertText),
            Color(p.metalLight), Color(p.metalMid), Color(p.metalDark), p.tartan.map { Color(it) },
        )
    }
}

/**
 * How much motion and visual effect to spend.
 * [reduced]: follow the accessibility "remove animations" setting (or user choice).
 * [lite]: entry-level hardware; glows and layered gradients are dropped, protection is not.
 */
@Immutable
data class RgMotion(val reduced: Boolean, val lite: Boolean)

val LocalRgColors = staticCompositionLocalOf { RgColors.from(Palettes.midnightDark) }
val LocalRgMotion = staticCompositionLocalOf { RgMotion(reduced = false, lite = false) }
val LocalThemeId = staticCompositionLocalOf { ThemeId.HIGHLAND_MIDNIGHT }

object Rg {
    val colors: RgColors @Composable get() = LocalRgColors.current
    val motion: RgMotion @Composable get() = LocalRgMotion.current
    val theme: ThemeId @Composable get() = LocalThemeId.current
}

val Cinzel = FontFamily(
    Font(R.font.cinzel_semibold, FontWeight.SemiBold),
    Font(R.font.cinzel_bold, FontWeight.Bold),
)

private fun typography(c: RgColors): Typography {
    val base = Typography()
    fun TextStyle.engraved(size: Int, spacing: Double, weight: FontWeight = FontWeight.SemiBold) =
        copy(fontFamily = Cinzel, fontWeight = weight, fontSize = size.sp, letterSpacing = spacing.sp, lineHeight = (size * 1.25).sp)
    return base.copy(
        displayLarge = base.displayLarge.engraved(40, 2.0, FontWeight.Bold),
        displayMedium = base.displayMedium.engraved(32, 1.6, FontWeight.Bold),
        displaySmall = base.displaySmall.engraved(26, 1.2, FontWeight.Bold),
        headlineLarge = base.headlineLarge.engraved(26, 1.0),
        headlineMedium = base.headlineMedium.engraved(22, 0.8),
        headlineSmall = base.headlineSmall.engraved(19, 0.6),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
    ).let { t ->
        // Body copy stays in the system face for readability at every size.
        t.copy(bodyLarge = t.bodyLarge.copy(color = c.text), bodyMedium = t.bodyMedium.copy(color = c.textSecondary))
    }
}

private fun scheme(c: RgColors): ColorScheme {
    val s = if (c.isDark) darkColorScheme() else lightColorScheme()
    return s.copy(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.surfaceRaised, onPrimaryContainer = c.text,
        secondary = c.protection, onSecondary = Color.White,
        secondaryContainer = c.surfaceRaised, onSecondaryContainer = c.text,
        tertiary = c.privacy, onTertiary = Color.White,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.surfaceSunken, surfaceContainerLow = c.surface,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
        surfaceBright = c.surfaceRaised, surfaceDim = c.surfaceSunken,
        inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = c.accentText,
        outline = c.outlineStrong, outlineVariant = c.outline,
        error = c.alert, onError = Color.White, errorContainer = c.surfaceRaised, onErrorContainer = c.alertText,
        scrim = Color.Black,
    )
}

/** Resolves which palette applies right now, honouring the brightness schedule. */
fun resolveTheme(s: Settings, systemDark: Boolean, minuteOfDay: Int): Pair<ThemeId, Boolean> {
    val inSchedule = run {
        val from = s.scheduleDarkFromMinutes
        val until = s.scheduleDarkUntilMinutes
        if (from <= until) minuteOfDay in from until until else minuteOfDay >= from || minuteOfDay < until
    }
    val dark = when (s.brightness) {
        Brightness.SYSTEM -> systemDark
        Brightness.LIGHT -> false
        Brightness.DARK -> true
        Brightness.SCHEDULED -> inSchedule
    }
    val theme = if (dark && s.nightTheme != null) s.nightTheme else s.theme
    return theme to (dark || theme.darkOnly)
}

fun systemAnimationsOff(context: Context): Boolean =
    runCatching { SysSettings.Global.getFloat(context.contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/** Entry-level device heuristic: low-RAM flag, under 3 GB RAM, or fewer than 6 cores. */
fun isLiteDevice(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return am.isLowRamDevice || mi.totalMem < 3L * 1024 * 1024 * 1024 || Runtime.getRuntime().availableProcessors() < 6
}

private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

@Composable
fun RocksGuardTheme(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    // Re-evaluated exactly at the next schedule boundary; no polling in between.
    var minute by remember { mutableIntStateOf(minuteNow()) }
    if (settings.brightness == Brightness.SCHEDULED) {
        LaunchedEffect(settings.scheduleDarkFromMinutes, settings.scheduleDarkUntilMinutes, minute) {
            val now = minuteNow()
            val targets = listOf(settings.scheduleDarkFromMinutes, settings.scheduleDarkUntilMinutes)
            val wait = targets.minOf { t -> ((t - now) + 1440) % 1440 }.let { if (it == 0) 1440 else it }
            delay(wait * 60_000L - (System.currentTimeMillis() % 60_000))
            minute = minuteNow()
        }
    }
    val (themeId, dark) = resolveTheme(settings, systemDark, minute)
    val palette = remember(themeId, dark, settings.customAccent) {
        val base = Palettes.of(themeId, dark)
        settings.customAccent?.let { Palettes.withAccent(base, it) } ?: base
    }
    val colors = remember(palette) { RgColors.from(palette) }
    val motion = remember(settings.motion, settings.effects) {
        RgMotion(
            reduced = when (settings.motion) {
                MotionPref.SYSTEM -> systemAnimationsOff(context)
                MotionPref.REDUCED -> true
                MotionPref.FULL -> false
            },
            lite = when (settings.effects) {
                EffectsPref.AUTO -> isLiteDevice(context)
                EffectsPref.LITE -> true
                EffectsPref.FULL -> false
            },
        )
    }
    CompositionLocalProvider(LocalRgColors provides colors, LocalRgMotion provides motion, LocalThemeId provides themeId) {
        MaterialTheme(colorScheme = scheme(colors), typography = typography(colors), content = content)
    }
}

/** Theme wrapper for previews and screenshot tests. */
@Composable
fun PreviewTheme(theme: ThemeId = ThemeId.HIGHLAND_MIDNIGHT, dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = RgColors.from(Palettes.of(theme, dark))
    CompositionLocalProvider(LocalRgColors provides colors, LocalRgMotion provides RgMotion(reduced = true, lite = false), LocalThemeId provides theme) {
        MaterialTheme(colorScheme = scheme(colors), typography = typography(colors), content = content)
    }
}
