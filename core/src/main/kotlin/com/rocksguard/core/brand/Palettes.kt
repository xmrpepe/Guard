package com.rocksguard.core.brand

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Highland Shield design tokens. Colours are plain ARGB ints so they can be verified
 * for WCAG contrast in JVM unit tests; the app maps them to Compose colours.
 */
data class Palette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceSunken: Int,
    val outline: Int,
    val outlineStrong: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    /** Brand fill (buttons, switches, selected states). */
    val accent: Int,
    val onAccent: Int,
    /** Accent used for text and icons on [background]/[surface]. */
    val accentText: Int,
    val protection: Int,
    val protectionText: Int,
    val privacy: Int,
    val privacyText: Int,
    val premium: Int,
    val premiumText: Int,
    val alert: Int,
    val alertText: Int,
    /** Shield metal gradient: highlight, body, shadow. */
    val metalLight: Int,
    val metalMid: Int,
    val metalDark: Int,
    /** Tartan sett colours for themes that use tartan accents (empty = none). */
    val tartan: List<Int> = emptyList(),
)

enum class ThemeId(val title: String, val description: String, val darkOnly: Boolean = false) {
    HIGHLAND_MIDNIGHT("Highland Midnight", "Dark granite, Saltire blue and silver"),
    ROYAL_EDINBURGH("Royal Edinburgh", "Deep navy, gold and polished stone"),
    THISTLE_HEATHER("Thistle & Heather", "Purple, charcoal and muted lavender"),
    CALEDONIAN_FOREST("Caledonian Forest", "Deep forest green and dark wood"),
    HIGHLAND_WINTER("Highland Winter", "Frosted silver, slate and pale blue"),
    SCOTTISH_HERITAGE("Scottish Heritage", "Tartan accents and warm stone"),
    FORTRESS_BLACK("Fortress Black", "Pure-black AMOLED with restrained blue", darkOnly = true),
}

object Palettes {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    // Brand constants from the Highland Shield specification.
    val HIGHLAND_MIDNIGHT_HEX = c(0x101820)
    val GRANITE_BLACK = c(0x171C22)
    val SALTIRE_BLUE = c(0x0065BD)
    val THISTLE_PURPLE = c(0x8064A2)
    val HIGHLAND_GREEN = c(0x287C63)
    val SILVER_MIST = c(0xC4CDD5)
    val HEATHER_GOLD = c(0xD5AA62)
    val WARNING_RED = c(0xC44545)

    /** RocksGuard's own tartan sett: Saltire blue, granite, highland green, heather gold. */
    val ROCKSGUARD_SETT = listOf(c(0x1B2A4A), c(0x1F4D3A), c(0x8C2A25), c(0xD5AA62), c(0x0B0F14))

    fun of(theme: ThemeId, dark: Boolean): Palette = when (theme) {
        ThemeId.HIGHLAND_MIDNIGHT -> if (dark) midnightDark else midnightLight
        ThemeId.ROYAL_EDINBURGH -> if (dark) edinburghDark else edinburghLight
        ThemeId.THISTLE_HEATHER -> if (dark) thistleDark else thistleLight
        ThemeId.CALEDONIAN_FOREST -> if (dark) forestDark else forestLight
        ThemeId.HIGHLAND_WINTER -> if (dark) winterDark else winterLight
        ThemeId.SCOTTISH_HERITAGE -> if (dark) heritageDark else heritageLight
        ThemeId.FORTRESS_BLACK -> fortressBlack
    }

    val midnightDark = Palette(
        isDark = true,
        background = c(0x101820), surface = c(0x171C22), surfaceRaised = c(0x1E252D), surfaceSunken = c(0x0B1117),
        outline = c(0x2A333D), outlineStrong = c(0x46525E),
        text = c(0xEDF1F5), textSecondary = c(0xC4CDD5), textTertiary = c(0x95A1AD),
        accent = SALTIRE_BLUE, onAccent = c(0xFFFFFF), accentText = c(0x62AEF2),
        protection = HIGHLAND_GREEN, protectionText = c(0x5CCBA5),
        privacy = THISTLE_PURPLE, privacyText = c(0xB9A1DA),
        premium = HEATHER_GOLD, premiumText = c(0xDDB676),
        alert = WARNING_RED, alertText = c(0xF08585),
        metalLight = c(0xEEF2F6), metalMid = c(0xA3AEBA), metalDark = c(0x4A5560),
    )

    val midnightLight = Palette(
        isDark = false,
        background = c(0xF2F4F7), surface = c(0xFFFFFF), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xE5E9EE),
        outline = c(0xD4DAE0), outlineStrong = c(0x9AA5B0),
        text = c(0x101820), textSecondary = c(0x3A4652), textTertiary = c(0x5A6672),
        accent = SALTIRE_BLUE, onAccent = c(0xFFFFFF), accentText = c(0x00579F),
        protection = HIGHLAND_GREEN, protectionText = c(0x1E6550),
        privacy = THISTLE_PURPLE, privacyText = c(0x664A88),
        premium = HEATHER_GOLD, premiumText = c(0x7F5B1B),
        alert = WARNING_RED, alertText = c(0xA52F2F),
        metalLight = c(0xFFFFFF), metalMid = c(0xBAC4CE), metalDark = c(0x5D6975),
    )

    val edinburghDark = Palette(
        isDark = true,
        background = c(0x0C1426), surface = c(0x121C33), surfaceRaised = c(0x19253F), surfaceSunken = c(0x08101F),
        outline = c(0x26334F), outlineStrong = c(0x45557A),
        text = c(0xF3EFE6), textSecondary = c(0xD4CCBA), textTertiary = c(0xA59D8A),
        accent = HEATHER_GOLD, onAccent = c(0x1A1408), accentText = c(0xE3C07F),
        protection = c(0x2F8A6C), protectionText = c(0x64D0A8),
        privacy = c(0x8E72B2), privacyText = c(0xC0AADF),
        premium = HEATHER_GOLD, premiumText = c(0xE3C07F),
        alert = WARNING_RED, alertText = c(0xF28B8B),
        metalLight = c(0xF8E8BE), metalMid = c(0xC9A15A), metalDark = c(0x6B5126),
    )

    val edinburghLight = Palette(
        isDark = false,
        background = c(0xF4EFE5), surface = c(0xFFFCF6), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xE9E2D4),
        outline = c(0xDCD3C2), outlineStrong = c(0xA99C82),
        text = c(0x0C1426), textSecondary = c(0x33405C), textTertiary = c(0x56607A),
        accent = c(0x1B2E5A), onAccent = c(0xF6E7C6), accentText = c(0x1B2E5A),
        protection = c(0x23705A), protectionText = c(0x1D604C),
        privacy = THISTLE_PURPLE, privacyText = c(0x634786),
        premium = HEATHER_GOLD, premiumText = c(0x7A5717),
        alert = WARNING_RED, alertText = c(0xA22E2E),
        metalLight = c(0xFFF6DE), metalMid = c(0xD5AA62), metalDark = c(0x7A5717),
    )

    val thistleDark = Palette(
        isDark = true,
        background = c(0x15121B), surface = c(0x1C1824), surfaceRaised = c(0x241F2E), surfaceSunken = c(0x0F0D14),
        outline = c(0x372F44), outlineStrong = c(0x5A4F6B),
        text = c(0xF0EBF6), textSecondary = c(0xCEC4DB), textTertiary = c(0xA196B0),
        accent = THISTLE_PURPLE, onAccent = c(0xFFFFFF), accentText = c(0xC3ABE3),
        protection = c(0x2F8A6C), protectionText = c(0x66CFA9),
        privacy = c(0xC08BB5), privacyText = c(0xE0AFD5),
        premium = HEATHER_GOLD, premiumText = c(0xDDB676),
        alert = WARNING_RED, alertText = c(0xF28B8B),
        metalLight = c(0xF1EAF8), metalMid = c(0xA899BD), metalDark = c(0x4D4160),
    )

    val thistleLight = Palette(
        isDark = false,
        background = c(0xF6F3F9), surface = c(0xFFFFFF), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xEAE4F1),
        outline = c(0xDCD3E6), outlineStrong = c(0xA597B8),
        text = c(0x1D1727), textSecondary = c(0x433A52), textTertiary = c(0x625872),
        accent = c(0x6E5291), onAccent = c(0xFFFFFF), accentText = c(0x5E4382),
        protection = c(0x23705A), protectionText = c(0x1D604C),
        privacy = c(0x9A5C8C), privacyText = c(0x82457A),
        premium = HEATHER_GOLD, premiumText = c(0x7A5717),
        alert = WARNING_RED, alertText = c(0xA22E2E),
        metalLight = c(0xFFFFFF), metalMid = c(0xC5B7D8), metalDark = c(0x5E4B78),
    )

    val forestDark = Palette(
        isDark = true,
        background = c(0x0E1611), surface = c(0x1A1813), surfaceRaised = c(0x23201A), surfaceSunken = c(0x0A100C),
        outline = c(0x37322A), outlineStrong = c(0x5A5244),
        text = c(0xEEF1EA), textSecondary = c(0xCBD2C3), textTertiary = c(0x9DA693),
        accent = HIGHLAND_GREEN, onAccent = c(0xFFFFFF), accentText = c(0x63C9A1),
        protection = c(0x2F8A6C), protectionText = c(0x63C9A1),
        privacy = c(0x8E72B2), privacyText = c(0xBDA5DD),
        premium = c(0xC99A55), premiumText = c(0xD9B07A),
        alert = WARNING_RED, alertText = c(0xF28B8B),
        metalLight = c(0xE4EBDF), metalMid = c(0x8FA08A), metalDark = c(0x3C4A3B),
    )

    val forestLight = Palette(
        isDark = false,
        background = c(0xF1F3EC), surface = c(0xFFFFFC), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xE3E7DA),
        outline = c(0xD3D9C8), outlineStrong = c(0x9AA48D),
        text = c(0x142019), textSecondary = c(0x37463B), textTertiary = c(0x56645A),
        accent = c(0x23705A), onAccent = c(0xFFFFFF), accentText = c(0x1D604C),
        protection = c(0x23705A), protectionText = c(0x1D604C),
        privacy = THISTLE_PURPLE, privacyText = c(0x634786),
        premium = c(0xB98745), premiumText = c(0x75521A),
        alert = WARNING_RED, alertText = c(0xA22E2E),
        metalLight = c(0xFFFFFF), metalMid = c(0xB9C4B2), metalDark = c(0x4F5E4C),
    )

    val winterLight = Palette(
        isDark = false,
        background = c(0xEEF2F6), surface = c(0xFAFCFD), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xDFE6ED),
        outline = c(0xCDD6DF), outlineStrong = c(0x95A4B3),
        text = c(0x1B2733), textSecondary = c(0x3C4B59), textTertiary = c(0x5A6977),
        accent = c(0x2F6FA7), onAccent = c(0xFFFFFF), accentText = c(0x245A89),
        protection = c(0x23705A), protectionText = c(0x1D604C),
        privacy = THISTLE_PURPLE, privacyText = c(0x634786),
        premium = c(0x9A7A45), premiumText = c(0x6E5420),
        alert = WARNING_RED, alertText = c(0xA22E2E),
        metalLight = c(0xFFFFFF), metalMid = c(0xC9D5E0), metalDark = c(0x6B7C8B),
    )

    val winterDark = Palette(
        isDark = true,
        background = c(0x151C23), surface = c(0x1C252E), surfaceRaised = c(0x242F39), surfaceSunken = c(0x10161C),
        outline = c(0x33404C), outlineStrong = c(0x556575),
        text = c(0xEDF2F6), textSecondary = c(0xC8D3DD), textTertiary = c(0x9AA8B5),
        accent = c(0x2F6FA7), onAccent = c(0xFFFFFF), accentText = c(0x93C6EE),
        protection = c(0x2F8A6C), protectionText = c(0x66CFA9),
        privacy = THISTLE_PURPLE, privacyText = c(0xBDA5DD),
        premium = HEATHER_GOLD, premiumText = c(0xDDB676),
        alert = WARNING_RED, alertText = c(0xF28B8B),
        metalLight = c(0xF4F8FB), metalMid = c(0xB4C3D0), metalDark = c(0x5E6F7E),
    )

    val heritageDark = Palette(
        isDark = true,
        background = c(0x17130F), surface = c(0x201A15), surfaceRaised = c(0x29221C), surfaceSunken = c(0x100D0A),
        outline = c(0x3D332A), outlineStrong = c(0x625446),
        text = c(0xF3EDE4), textSecondary = c(0xD5CABC), textTertiary = c(0xA7998A),
        accent = c(0x24477A), onAccent = c(0xFFFFFF), accentText = c(0x93B5E8),
        protection = c(0x2F8A6C), protectionText = c(0x66CFA9),
        privacy = THISTLE_PURPLE, privacyText = c(0xBDA5DD),
        premium = HEATHER_GOLD, premiumText = c(0xDDB676),
        alert = WARNING_RED, alertText = c(0xF28B8B),
        metalLight = c(0xF5E9D6), metalMid = c(0xB8A58A), metalDark = c(0x5C4B38),
        tartan = ROCKSGUARD_SETT,
    )

    val heritageLight = Palette(
        isDark = false,
        background = c(0xF4EEE5), surface = c(0xFFFBF5), surfaceRaised = c(0xFFFFFF), surfaceSunken = c(0xE8DFD2),
        outline = c(0xDCD0BF), outlineStrong = c(0xA8977F),
        text = c(0x2A2018), textSecondary = c(0x4E4034), textTertiary = c(0x6A5B4D),
        accent = c(0x24477A), onAccent = c(0xFFFFFF), accentText = c(0x24477A),
        protection = c(0x23705A), protectionText = c(0x1D604C),
        privacy = THISTLE_PURPLE, privacyText = c(0x634786),
        premium = HEATHER_GOLD, premiumText = c(0x75521A),
        alert = WARNING_RED, alertText = c(0xA22E2E),
        metalLight = c(0xFFF8EC), metalMid = c(0xCDB99C), metalDark = c(0x6E5A42),
        tartan = ROCKSGUARD_SETT,
    )

    val fortressBlack = Palette(
        isDark = true,
        background = c(0x000000), surface = c(0x0A0C0F), surfaceRaised = c(0x12161B), surfaceSunken = c(0x000000),
        outline = c(0x1E242B), outlineStrong = c(0x3A444E),
        text = c(0xE8ECF0), textSecondary = c(0xB6C0CA), textTertiary = c(0x8A96A2),
        accent = SALTIRE_BLUE, onAccent = c(0xFFFFFF), accentText = c(0x5AA5EC),
        protection = HIGHLAND_GREEN, protectionText = c(0x58C49F),
        privacy = THISTLE_PURPLE, privacyText = c(0xB39BD6),
        premium = HEATHER_GOLD, premiumText = c(0xD5AA62),
        alert = WARNING_RED, alertText = c(0xEE7F7F),
        metalLight = c(0xC9D2DB), metalMid = c(0x6E7B88), metalDark = c(0x2A323A),
    )

    /**
     * Applies a user-chosen accent. The fill keeps the chosen hue; text/icon variants are
     * nudged in lightness until they reach WCAG AA (4.5:1) on the theme's surfaces.
     */
    fun withAccent(p: Palette, accent: Int): Palette {
        val onAccent = if (ColorMath.contrast(0xFFFFFFFF.toInt(), accent) >= ColorMath.contrast(0xFF101820.toInt(), accent)) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF101820.toInt()
        }
        val text = ColorMath.ensureContrast(accent, listOf(p.background, p.surface, p.surfaceRaised), 4.5)
        return p.copy(accent = accent, onAccent = onAccent, accentText = text)
    }
}

object ColorMath {
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Mixes [a] toward [b] by [t] (0..1) in sRGB space. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
            (m((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or m(a and 0xFF, b and 0xFF)
    }

    /** Lightens or darkens [color] until it meets [ratio] against every background. */
    fun ensureContrast(color: Int, backgrounds: List<Int>, ratio: Double): Int {
        val darkBg = backgrounds.all { luminance(it) < 0.2 }
        val target = if (darkBg) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        var t = 0.0
        var c = color
        while (t <= 1.0) {
            c = mix(color, target, t)
            if (backgrounds.all { contrast(c, it) >= ratio }) return c
            t += 0.04
        }
        return c
    }
}
