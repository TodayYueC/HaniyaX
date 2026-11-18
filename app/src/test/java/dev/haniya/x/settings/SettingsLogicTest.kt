package dev.haniya.x.settings

import dev.haniya.relief.theme.ReliefPalette
import dev.haniya.x.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    @Test fun phantomGuidePerAndroidVersion() {
        val a11 = BackgroundGuide.forSdk(30)
        assertFalse(a11.limited)
        assertTrue(a11.commands.isEmpty())

        val a12 = BackgroundGuide.forSdk(31)
        assertTrue(a12.limited)
        assertFalse(a12.devOptionsSwitch)
        assertEquals(listOf(BackgroundGuide.CMD_DEVICE_CONFIG), a12.commands)

        for (sdk in listOf(32, 33)) {
            val g = BackgroundGuide.forSdk(sdk)
            assertTrue(g.limited)
            assertFalse(g.devOptionsSwitch)
            assertEquals(listOf(BackgroundGuide.CMD_FEATURE_FLAG), g.commands)
        }
        for (sdk in listOf(34, 35, 36, 40)) assertTrue(BackgroundGuide.forSdk(sdk).devOptionsSwitch)
    }

    @Test fun adbCommandsAreExact() {
        assertEquals("adb shell settings put global settings_enable_monitor_phantom_procs false", BackgroundGuide.CMD_FEATURE_FLAG)
        assertEquals("adb shell device_config put activity_manager max_phantom_processes 2147483647", BackgroundGuide.CMD_DEVICE_CONFIG)
    }

    @Test fun androidNames() {
        assertEquals("Android 12L", BackgroundGuide.androidName(32))
        assertEquals("Android 14", BackgroundGuide.androidName(34))
        assertEquals("Android（API 99）", BackgroundGuide.androidName(99))
    }

    @Test fun resetNeedsTheExactWord() {
        assertTrue(ResetConfirm.matches("重置"))
        assertTrue(ResetConfirm.matches(" 重置 "))
        assertFalse(ResetConfirm.matches("重"))
        assertFalse(ResetConfirm.matches(""))
        assertFalse(ResetConfirm.matches("重置工作区"))
    }

    @Test fun pasteSteps() {
        assertEquals(500, PasteSteps.chars(400, up = true))
        assertEquals(1500, PasteSteps.chars(1000, up = true))
        assertEquals(1000, PasteSteps.chars(1500, up = false))
        assertEquals(900, PasteSteps.chars(1000, up = false))
        assertEquals(SettingsStore.PASTE_CHARS_MIN, PasteSteps.chars(SettingsStore.PASTE_CHARS_MIN, up = false))
        assertEquals(SettingsStore.PASTE_CHARS_MAX, PasteSteps.chars(SettingsStore.PASTE_CHARS_MAX, up = true))
        assertEquals(4, PasteSteps.lines(3, up = true))
        assertEquals(SettingsStore.PASTE_LINES_MIN, PasteSteps.lines(1, up = false))
        assertEquals(SettingsStore.PASTE_LINES_MAX, PasteSteps.lines(SettingsStore.PASTE_LINES_MAX, up = true))
    }

    @Test fun sanitizeClampsOutOfRangeValues() {
        val s = SettingsStore.sanitize(AppSettings(terminalFontSp = 3, pastePreviewChars = 1_000_000, pastePreviewLines = 0))
        assertEquals(SettingsStore.FONT_MIN, s.terminalFontSp)
        assertEquals(SettingsStore.PASTE_CHARS_MAX, s.pastePreviewChars)
        assertEquals(SettingsStore.PASTE_LINES_MIN, s.pastePreviewLines)
        val ok = AppSettings(terminalFontSp = 15)
        assertEquals(ok, SettingsStore.sanitize(ok))
    }

    @Test fun settingsJsonRoundTripAndDefaults() {
        val s = AppSettings(palette = ReliefPalette.ROSE, terminalFontSp = 16, wakeLock = true, agentSdkHints = false)
        assertEquals(s, AppJson.decodeFromString(AppSettings.serializer(), AppJson.encodeToString(AppSettings.serializer(), s)))
        // Older or hand-written files with unknown keys and missing fields still load.
        assertEquals(AppSettings(terminalFontSp = 20), AppJson.decodeFromString(AppSettings.serializer(), """{"terminalFontSp":20,"future":"x"}"""))
        assertEquals(AppSettings(), AppJson.decodeFromString(AppSettings.serializer(), "{}"))
    }
}
