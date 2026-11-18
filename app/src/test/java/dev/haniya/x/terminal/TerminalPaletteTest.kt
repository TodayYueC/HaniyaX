package dev.haniya.x.terminal

import dev.haniya.relief.theme.ReliefPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class TerminalPaletteTest {

    /** Token values from docs/design/references/tokens.md (bg, ink, accent, mid = lerp(accent, acc2, .35) approx). */
    private data class Tokens(val bg: Int, val ink: Int, val accent: Int, val dark: Boolean)

    private val tokens = mapOf(
        ReliefPalette.PLATINUM to Tokens(0xFFE6EAF1.toInt(), 0xFF2F3648.toInt(), 0xFF6A86F2.toInt(), false),
        ReliefPalette.MOONSTONE to Tokens(0xFFE9E7EF.toInt(), 0xFF332F45.toInt(), 0xFF8072E0.toInt(), false),
        ReliefPalette.CHAMPAGNE to Tokens(0xFFEFEBE5.toInt(), 0xFF3B342D.toInt(), 0xFFBA8B5C.toInt(), false),
        ReliefPalette.ROSE to Tokens(0xFFEFE8EB.toInt(), 0xFF3C2F37.toInt(), 0xFFC77391.toInt(), false),
        ReliefPalette.OBSIDIAN to Tokens(0xFF262A33.toInt(), 0xFFE8EBF3.toInt(), 0xFF7E95FF.toInt(), true),
    )

    private fun palette(p: ReliefPalette): TerminalPalette {
        val t = tokens.getValue(p)
        return TerminalPalette.build(p, t.dark, t.bg, t.ink, t.accent, t.accent)
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun everyPaletteIsCovered() {
        assertEquals(ReliefPalette.entries.toSet(), tokens.keys)
        for (p in ReliefPalette.entries) assertEquals(16, AnsiTables.of(p).size)
    }

    @Test
    fun normalColoursReachAaContrast() {
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            for (i in (1..7) + (9..15)) {
                val ratio = ColorMath.contrast(tp.ansi[i], tp.background)
                assertTrue("$p color$i ${hex(tp.ansi[i])} on ${hex(tp.background)} = $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun blackAndBrightBlackStayReadable() {
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            val c0 = ColorMath.contrast(tp.ansi[0], tp.background)
            val c8 = ColorMath.contrast(tp.ansi[8], tp.background)
            assertTrue("$p color0 = $c0", c0 >= 3.0)
            assertTrue("$p color8 (dimmed text) = $c8", c8 >= 3.5)
        }
    }

    @Test
    fun foregroundReadableOnBackgroundSelectionAndHighlight() {
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            assertTrue("$p fg/bg", ColorMath.contrast(tp.foreground, tp.background) >= 7.0)
            assertTrue("$p fg/selection", ColorMath.contrast(tp.foreground, tp.selection) >= 4.5)
            assertTrue("$p fg/search highlight", ColorMath.contrast(tp.foreground, tp.searchHighlight) >= 4.5)
            // Selection and highlight must be visible on the background and differ from each other.
            assertTrue("$p selection visible", ColorMath.contrast(tp.selection, tp.background) >= 1.15)
            assertTrue("$p highlight visible", ColorMath.contrast(tp.searchHighlight, tp.background) >= 1.15)
            assertTrue("$p selection != highlight", oklabDistance(tp.selection, tp.searchHighlight) > 0.07)
        }
    }

    @Test
    fun redAndGreenAreClearlyDistinct() {
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            for ((r, g) in listOf(1 to 2, 9 to 10)) {
                val normal = oklabDistance(tp.ansi[r], tp.ansi[g])
                val deutan = oklabDistance(simulate(tp.ansi[r], DEUTAN), simulate(tp.ansi[g], DEUTAN))
                val protan = oklabDistance(simulate(tp.ansi[r], PROTAN), simulate(tp.ansi[g], PROTAN))
                assertTrue("$p red/green ΔE=$normal", normal >= 0.15)
                assertTrue("$p red/green under deuteranopia ΔE=$deutan", deutan >= 0.04)
                assertTrue("$p red/green under protanopia ΔE=$protan", protan >= 0.06)
            }
        }
    }

    @Test
    fun redComesFromTheDangerHueFamily() {
        // danger #B4566A has an OKLCH hue of ≈ 8°; terminal red must stay within ±20° of it.
        val dangerHue = hue(0xFFB4566A.toInt())
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            for (i in listOf(1, 9)) {
                val d = hueDistance(hue(tp.ansi[i]), dangerHue)
                assertTrue("$p color$i hue distance $d", d <= 20.0)
            }
        }
    }

    @Test
    fun hueFamiliesAreDistinct() {
        // red, green, yellow, blue, magenta, cyan must not collapse into each other.
        for (p in ReliefPalette.entries) {
            val tp = palette(p)
            val hues = (1..6).map { hue(tp.ansi[it]) }
            for (a in hues.indices) for (b in hues.indices) if (a < b) {
                assertTrue("$p colors ${a + 1}/${b + 1} too close", hueDistance(hues[a], hues[b]) >= 30.0)
            }
        }
    }

    @Test
    fun colorFgBgMatchesBrightness() {
        assertEquals("0;15", palette(ReliefPalette.PLATINUM).colorFgBg)
        assertEquals("15;0", palette(ReliefPalette.OBSIDIAN).colorFgBg)
    }

    @Test
    fun contrastMathMatchesWcagReference() {
        assertEquals(21.0, ColorMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(1.0, ColorMath.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-9)
        // #767676 on white is the classic 4.54:1 example.
        assertEquals(4.54, ColorMath.contrast(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0xFF808080.toInt(), ColorMath.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    // --- OKLab helpers (test-only) -----------------------------------------------------------------------------

    private fun lin(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun rgbLinear(c: Int) = doubleArrayOf(lin((c shr 16) and 0xFF), lin((c shr 8) and 0xFF), lin(c and 0xFF))

    private fun oklab(rgb: DoubleArray): DoubleArray {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun oklabDistance(a: Int, b: Int) = oklabDistance(rgbLinear(a), rgbLinear(b))

    private fun oklabDistance(a: DoubleArray, b: DoubleArray): Double {
        val x = oklab(a)
        val y = oklab(b)
        return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
    }

    private fun hue(c: Int): Double {
        val lab = oklab(rgbLinear(c))
        val h = Math.toDegrees(atan2(lab[2], lab[1]))
        return if (h < 0) h + 360 else h
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Machado et al. 2009 full-severity simulation matrices (linear RGB). */
    private val DEUTAN = arrayOf(
        doubleArrayOf(0.367322, 0.860646, -0.227968),
        doubleArrayOf(0.280085, 0.672501, 0.047413),
        doubleArrayOf(-0.011820, 0.042940, 0.968881),
    )
    private val PROTAN = arrayOf(
        doubleArrayOf(0.152286, 1.052583, -0.204868),
        doubleArrayOf(0.114503, 0.786281, 0.099216),
        doubleArrayOf(-0.003882, -0.048116, 1.051998),
    )

    private fun simulate(c: Int, m: Array<DoubleArray>): DoubleArray {
        val v = rgbLinear(c)
        return DoubleArray(3) { i -> (m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]).coerceIn(0.0, 1.0) }
    }

    @Suppress("unused")
    private fun chroma(c: Int): Double {
        val lab = oklab(rgbLinear(c))
        return hypot(lab[1], lab[2])
    }
}
