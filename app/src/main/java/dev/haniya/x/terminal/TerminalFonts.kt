package dev.haniya.x.terminal

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log

/**
 * Terminal typeface: JetBrains Mono (consistent cell metrics on every phone), then Noto Sans Symbols 2 for glyphs
 * TUIs use that phone fonts often lack (⏵ ⏺ ⏸, braille spinners …), then the system fallback chain (CJK, emoji).
 * Android 8–9 have no custom fallback API and use the system monospace font.
 */
object TerminalFonts {
    @Volatile private var cached: Typeface? = null

    fun typeface(context: Context): Typeface {
        cached?.let { return it }
        val tf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) build(context) ?: Typeface.MONOSPACE else Typeface.MONOSPACE
        cached = tf
        return tf
    }

    private fun build(context: Context): Typeface? = try {
        val am = context.assets
        val mono = FontFamily.Builder(Font.Builder(am, "fonts/JetBrainsMono.ttf").setWeight(400).build()).build()
        val symbols = FontFamily.Builder(Font.Builder(am, "fonts/NotoSansSymbols2.ttf").build()).build()
        Typeface.CustomFallbackBuilder(mono)
            .addCustomFallback(symbols)
            .setSystemFallback("monospace")
            .build()
    } catch (e: Exception) {
        Log.w("HaniyaX.TermFonts", "custom terminal font unavailable", e)
        null
    }
}
