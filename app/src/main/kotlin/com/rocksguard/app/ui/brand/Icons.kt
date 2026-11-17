package com.rocksguard.app.ui.brand

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * RocksGuard's own line icons (24 dp grid, 1.8 dp stroke). Drawn for this app rather than
 * taken from a generic set, so each section has a recognisable heraldic object.
 */
object RgIcons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 1.8f, fill: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (fill) null else SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Fortress = icon("fortress", "M4 20V9h3v2h2.5V9h5v2H17V9h3v11Z", "M10 20v-4a2 2 0 0 1 4 0v4")
    val Lookout = icon("lookout", "M2.5 12C5 7.5 8.3 5.5 12 5.5S19 7.5 21.5 12C19 16.5 15.7 18.5 12 18.5S5 16.5 2.5 12Z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6Z")
    val Ramparts = icon("ramparts", "M5 21V7a7 4 0 0 1 14 0v14", "M9 4.6V20", "M12 3.2V20", "M15 4.6V20", "M5 10h14", "M5 14.5h14")
    val Watchtower = icon("watchtower", "M9 21l1-12h4l1 12Z", "M8 9V5h1.6v1.5h1.6V5h1.6v1.5h1.6V5H16v4Z", "M12 13v3", "M4.8 9.6a5 5 0 0 1 0-5.2", "M19.2 4.4a5 5 0 0 1 0 5.2")
    val Shield = icon("shield", "M12 3l7 3v5c0 4.6-3 8.3-7 10c-4-1.7-7-5.4-7-10V6Z", "M8.6 8.6l6.8 6.8", "M15.4 8.6l-6.8 6.8")
    val Keep = icon("keep", "M6 11h12v10H6Z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3", "M12 14.5v3")
    val Armoury = icon("armoury", "M12 3.5l8.5 4.5L12 12.5L3.5 8Z", "M3.5 12l8.5 4.5L20.5 12", "M3.5 16l8.5 4.5L20.5 16")
    val Workshop = icon("workshop", "M3.5 8.5h12.5c0 2 1.8 3.5 4.5 3.5v1h-5.5l-1.5 2.5v2h3V21H7.5v-3.5h3v-2L9 13H7.5C5.3 13 3.5 11 3.5 8.5Z")
    val More = icon("more", "M5 12h.01", "M12 12h.01", "M19 12h.01", stroke = 3.4f)
    val Thistle = icon(
        "thistle",
        "M8 11c0-2.5 1.8-4 4-4s4 1.5 4 4c0 2-1.8 3.5-4 3.5S8 13 8 11Z",
        "M9 7.5L7.5 3.5", "M10.5 7L10 2.8", "M12 6.8V2.5", "M13.5 7L14 2.8", "M15 7.5l1.5-4",
        "M12 14.5V21", "M12 18c-2-.2-4-1.4-5-3.5c2 0 3.8.8 5 2.2", "M12 17.5c2-.2 4-1.4 5-3.5c-2 0-3.8.8-5 2.2",
    )
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Cross = icon("cross", "M7 7l10 10", "M17 7L7 17")
    val Search = icon("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13Z", "M15.5 15.5L20 20")
    val Refresh = icon("refresh", "M19 12a7 7 0 1 1-2.05-4.95", "M19 4.5V8h-3.5")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Info = icon("info", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17Z", "M12 11v5.5", "M12 7.6v.01")
    val Wifi = icon("wifi", "M2.5 9a14 14 0 0 1 19 0", "M5.5 12.5a9.5 9.5 0 0 1 13 0", "M8.8 16a4.8 4.8 0 0 1 6.4 0", "M12 19.5v.01")
    val Mobile = icon("mobile", "M5 19v-3", "M9.5 19v-6", "M14 19v-9", "M18.5 19V5")
    val ChevronRight = icon("chevron", "M9.5 6l6 6-6 6")
    val Up = icon("up", "M6 15l6-6 6 6")
    val Down = icon("down", "M6 9l6 6 6-6")
    val Pause = icon("pause", "M8.5 5.5v13", "M15.5 5.5v13")
    val Play = icon("play", "M8 5.5l11 6.5-11 6.5Z")
    val Radar = icon("radar", "M12 3.5a8.5 8.5 0 1 0 8.5 8.5", "M12 12l6-6", "M12 8a4 4 0 1 0 4 4")
    val Warning = icon("warning", "M12 4L21 19.5H3Z", "M12 10v4.5", "M12 17.2v.01")
    val Copy = icon("copy", "M8.5 8.5h10v11h-10Z", "M5.5 15.5V4.5h10")
    val Globe = icon("globe", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17Z", "M3.5 12h17", "M12 3.5c2.5 2.5 3.5 5.3 3.5 8.5s-1 6-3.5 8.5c-2.5-2.5-3.5-5.3-3.5-8.5s1-6 3.5-8.5Z")
    val Trash = icon("trash", "M5 7h14", "M9.5 7V4.5h5V7", "M7 7l1 13h8l1-13")
    val Bypass = icon("bypass", "M4 12h9", "M10 8l4 4-4 4", "M17 5v14")
}
