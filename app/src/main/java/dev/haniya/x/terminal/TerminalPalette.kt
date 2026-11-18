package dev.haniya.x.terminal

import androidx.compose.ui.graphics.toArgb
import dev.haniya.relief.theme.ReliefColors
import dev.haniya.relief.theme.ReliefPalette
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Terminal colours derived from a Haniya Relief palette. All colours are opaque ARGB ints, the format the
 * Termux emulator and renderer use.
 *
 * - background = `bg`, foreground = `ink`, cursor = `mid`, selection ≈ `soft` (a slightly stronger accent wash so
 *   it stays visible on the ceramic background), search highlight = a wash of a hue that is clearly apart from the
 *   accent: the Relief amber ("notice") on cool palettes, a calm sky / teal on the warm Champagne / Rose palettes.
 * - The 16 ANSI colours are hand-tuned per palette (see [AnsiTables]): hues follow the palette's character, normal
 *   colours 1–7 / 9–15 reach ≥ 4.5:1 against the background, colour 8 (dimmed text in many TUIs) ≥ 3.5:1,
 *   colour 0 ≥ 3:1, red comes from the danger hue family and is kept clearly apart from green (also under
 *   red–green colour-vision deficiency) so errors and diffs read at a glance.
 */
data class TerminalPalette(
    val palette: ReliefPalette,
    val isDark: Boolean,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val searchHighlight: Int,
    /** ANSI colours 0–15. */
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == 16) { "ANSI table must have 16 colours" }
    }

    /** `COLORFGBG` value for programs that guess light/dark from it ("fg;bg" as ANSI indices). */
    val colorFgBg: String get() = if (isDark) "15;0" else "0;15"

    companion object {
        /** Builds the terminal palette for the given Relief colours and palette id. */
        fun from(colors: ReliefColors, palette: ReliefPalette): TerminalPalette {
            val bg = colors.bg.toArgb() or OPAQUE
            val ink = colors.ink.toArgb() or OPAQUE
            val accent = colors.accent.toArgb() or OPAQUE
            val mid = colors.mid.toArgb() or OPAQUE
            return build(palette, colors.isDark, bg, ink, accent, mid)
        }

        /** Pure builder (no Compose types) - also used by unit tests. */
        fun build(palette: ReliefPalette, isDark: Boolean, background: Int, foreground: Int, accent: Int, cursor: Int): TerminalPalette =
            TerminalPalette(
                palette = palette,
                isDark = isDark,
                background = background,
                foreground = foreground,
                cursor = cursor,
                selection = ColorMath.blend(background, accent, if (isDark) 0.30f else 0.22f),
                searchHighlight = ColorMath.blend(background, highlightHue(palette), if (isDark) 0.40f else 0.50f),
                ansi = AnsiTables.of(palette),
            )

        /** Hue washed into the background for the search highlight; kept away from the palette accent. */
        private fun highlightHue(palette: ReliefPalette): Int = when (palette) {
            ReliefPalette.CHAMPAGNE -> SKY
            ReliefPalette.ROSE -> TEAL
            ReliefPalette.PLATINUM, ReliefPalette.MOONSTONE, ReliefPalette.OBSIDIAN -> AMBER
        }

        private const val OPAQUE = 0xFF000000.toInt()
        /** Relief fixed amber (notices / needs-review). */
        private const val AMBER = 0xFFD2A26C.toInt()
        private const val SKY = 0xFF7FB2E6.toInt()
        private const val TEAL = 0xFF6FB7B4.toInt()
    }
}

/**
 * Hand-tuned ANSI tables. Tuned in OKLCH: each hue is picked from the palette's character (cool periwinkle for
 * Platinum, violet for Moonstone, warm brass for Champagne, rose for Rose, ice blue for Obsidian), then lightness is
 * set against that palette's background for the contrast targets below and chroma is kept calm.
 *
 * Light themes: colours 1–7 ≈ 4.8–6.4:1, bright 9–15 ≈ 4.6–5.6:1 (bright = more saturated), 0 ≈ 9:1, 8 ≈ 4.2:1.
 * Dark theme: 1–6 ≈ 5.6–7.4:1, bright ≈ 7.6–9.2:1, 7 ≈ 9:1, 15 ≈ 13.5:1, 0 ≈ 3.1:1, 8 ≈ 4.3:1.
 * Red (hue ≈ 8°, the danger hue) is lighter than green on light themes and darker on the dark theme so the pair
 * also separates by lightness.
 */
object AnsiTables {
    private fun c(v: Long): Int = v.toInt()

    private val PLATINUM = listOf(
        c(0xFF363D4D), c(0xFFA6445C), c(0xFF1F5F0C), c(0xFF825405),
        c(0xFF3A57A5), c(0xFF804691), c(0xFF026776), c(0xFF565D6F),
        c(0xFF686E7E), c(0xFFBB3654), c(0xFF006A12), c(0xFF8B5E03),
        c(0xFF445CC9), c(0xFF9843A6), c(0xFF077084), c(0xFF606776),
    )
    private val MOONSTONE = listOf(
        c(0xFF3C3A4C), c(0xFFA0475E), c(0xFF1F5E1A), c(0xFF835202),
        c(0xFF46559F), c(0xFF844583), c(0xFF006770), c(0xFF5C5B6D),
        c(0xFF6D6B7C), c(0xFFB53A58), c(0xFF02691F), c(0xFF8B5C03),
        c(0xFF5259C1), c(0xFF9E4194), c(0xFF03707F), c(0xFF666474),
    )
    private val CHAMPAGNE = listOf(
        c(0xFF4A3B2E), c(0xFF9F4D57), c(0xFF365D15), c(0xFF7F5705),
        c(0xFF275E98), c(0xFF8A4778), c(0xFF03696C), c(0xFF6B5C4E),
        c(0xFF7B6D61), c(0xFFB5414D), c(0xFF296A03), c(0xFF876103),
        c(0xFF2166BB), c(0xFFA54386), c(0xFF02737A), c(0xFF736559),
    )
    private val ROSE = listOf(
        c(0xFF4A3840), c(0xFFA04A5B), c(0xFF285E1B), c(0xFF825403),
        c(0xFF305B9C), c(0xFF824884), c(0xFF06686F), c(0xFF6D5862),
        c(0xFF7B6A71), c(0xFFB63D52), c(0xFF026A13), c(0xFF8B5E06),
        c(0xFF3262BE), c(0xFF9C4496), c(0xFF05727C), c(0xFF74626A),
    )
    private val OBSIDIAN = listOf(
        c(0xFF6D7587), c(0xFFE18999), c(0xFF91C985), c(0xFFD3AA59),
        c(0xFF8DA9F1), c(0xFFCA99DD), c(0xFF3DBECB), c(0xFFC7CDDA),
        c(0xFF868D9C), c(0xFFFEA4AE), c(0xFF94E193), c(0xFFEEC35C),
        c(0xFFB0C2FE), c(0xFFEEADFD), c(0xFF36D9EF), c(0xFFF5F8FF),
    )

    fun of(palette: ReliefPalette): List<Int> = when (palette) {
        ReliefPalette.PLATINUM -> PLATINUM
        ReliefPalette.MOONSTONE -> MOONSTONE
        ReliefPalette.CHAMPAGNE -> CHAMPAGNE
        ReliefPalette.ROSE -> ROSE
        ReliefPalette.OBSIDIAN -> OBSIDIAN
    }
}

/** Small colour helpers on ARGB ints (pure, JVM-testable). */
object ColorMath {
    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    /** Linear blend from [from] to [to] by [amount] (0..1) in sRGB; result is opaque. */
    fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(shift: Int) = (channel(from, shift) * (1 - t) + channel(to, shift) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun linear(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG 2.x relative luminance. */
    fun luminance(argb: Int): Double =
        0.2126 * linear(channel(argb, 16)) + 0.7152 * linear(channel(argb, 8)) + 0.0722 * linear(channel(argb, 0))

    /** WCAG 2.x contrast ratio (1..21). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
