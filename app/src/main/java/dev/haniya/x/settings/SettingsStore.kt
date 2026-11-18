package dev.haniya.x.settings

import dev.haniya.x.core.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** App settings persisted in Paths.state/settings.json. Loaded synchronously: it is the first store created. */
class SettingsStore : SettingsApi {
    private val file = JsonStateFile(File(Paths.state, "settings.json"), AppSettings.serializer()) { AppSettings() }
    private val state = MutableStateFlow(sanitize(file.load()))
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    init {
        file.autosave(state, state.value)
    }

    override fun update(transform: (AppSettings) -> AppSettings) {
        state.update { sanitize(transform(it)) }
    }

    companion object {
        const val FONT_MIN = 9
        const val FONT_MAX = 24
        const val PASTE_CHARS_MIN = 100
        const val PASTE_CHARS_MAX = 5000
        const val PASTE_LINES_MIN = 1
        const val PASTE_LINES_MAX = 50

        /** Clamps values a hand-edited or older file could carry out of range. */
        fun sanitize(s: AppSettings): AppSettings = s.copy(
            terminalFontSp = s.terminalFontSp.coerceIn(FONT_MIN, FONT_MAX),
            pastePreviewChars = s.pastePreviewChars.coerceIn(PASTE_CHARS_MIN, PASTE_CHARS_MAX),
            pastePreviewLines = s.pastePreviewLines.coerceIn(PASTE_LINES_MIN, PASTE_LINES_MAX),
        )
    }
}
