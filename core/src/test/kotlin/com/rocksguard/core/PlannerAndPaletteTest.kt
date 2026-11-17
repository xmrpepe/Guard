package com.rocksguard.core

import com.rocksguard.core.brand.ColorMath
import com.rocksguard.core.brand.Palette
import com.rocksguard.core.brand.Palettes
import com.rocksguard.core.brand.ThemeId
import com.rocksguard.core.engine.AppRule
import com.rocksguard.core.engine.GateMode
import com.rocksguard.core.engine.NetworkKind
import com.rocksguard.core.engine.TunnelPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerAndPaletteTest {
    private val own = "com.rocksguard.app"

    @Test fun sealedWithNoBlockedAppsFallsBackToWatch() {
        val plan = TunnelPlanner.plan(GateMode.SEALED, listOf(AppRule("a", 1, blockOnMobile = true)), NetworkKind.WIFI, own)
        assertEquals(GateMode.WATCH, plan.mode)
        assertFalse(plan.routeAllTraffic)
        assertTrue(own in plan.disallowedPackages)
        assertNotNull(plan.notice)
    }

    @Test fun sealedRoutesOnlyBlockedApps() {
        val rules = listOf(AppRule("a", 1, blockOnWifi = true), AppRule("b", 2, blockOnMobile = true), AppRule(own, 3, blockOnWifi = true))
        val plan = TunnelPlanner.plan(GateMode.SEALED, rules, NetworkKind.WIFI, own)
        assertEquals(GateMode.SEALED, plan.mode)
        assertEquals(listOf("a"), plan.allowedPackages)
        assertTrue(plan.routeAllTraffic)
    }

    @Test fun watchExcludesSelfAndBypassApps() {
        val plan = TunnelPlanner.plan(GateMode.WATCH, listOf(AppRule("bank", 9, bypass = true)), NetworkKind.MOBILE, own)
        assertEquals(listOf(own, "bank"), plan.disallowedPackages)
    }

    private fun check(name: String, p: Palette) {
        val bgs = listOf(p.background, p.surface, p.surfaceRaised)
        val failures = ArrayList<String>()
        fun need(label: String, fg: Int, min: Double, backgrounds: List<Int> = bgs) {
            for (bg in backgrounds) {
                val c = ColorMath.contrast(fg, bg)
                if (c < min) failures += "$name $label ${"%.2f".format(c)} < $min on #${Integer.toHexString(bg)}"
            }
        }
        need("text", p.text, 7.0)
        need("textSecondary", p.textSecondary, 4.5)
        need("textTertiary", p.textTertiary, 4.5)
        need("accentText", p.accentText, 4.5)
        need("protectionText", p.protectionText, 4.5)
        need("privacyText", p.privacyText, 4.5)
        need("premiumText", p.premiumText, 4.5)
        need("alertText", p.alertText, 4.5)
        need("onAccent", p.onAccent, 4.5, listOf(p.accent))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyThemeMeetsWcagAA() {
        for (t in ThemeId.entries) {
            check("${t.name}/dark", Palettes.of(t, true))
            if (!t.darkOnly) check("${t.name}/light", Palettes.of(t, false))
        }
    }

    @Test fun customAccentsStayReadable() {
        val accents = listOf(0xFFFF0000, 0xFFFFEB3B, 0xFF00BCD4, 0xFF3F51B5, 0xFF8BC34A, 0xFFE91E63, 0xFF795548).map { it.toInt() }
        for (t in ThemeId.entries) for (dark in listOf(true, false)) for (a in accents) {
            val p = Palettes.withAccent(Palettes.of(t, dark), a)
            for (bg in listOf(p.background, p.surface, p.surfaceRaised)) {
                assertTrue("${t.name} $dark ${Integer.toHexString(a)}", ColorMath.contrast(p.accentText, bg) >= 4.5)
            }
            assertTrue(ColorMath.contrast(p.onAccent, p.accent) >= 3.0)
        }
    }
}
