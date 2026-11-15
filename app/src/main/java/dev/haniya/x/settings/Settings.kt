package dev.haniya.x.settings

import dev.haniya.relief.theme.ReliefPalette
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** CONTRACT — owned by the settings module; persisted in Paths.state/settings.json. */
@Serializable
data class AppSettings(
    /** null = follow system (PLATINUM light / OBSIDIAN dark). */
    val palette: ReliefPalette? = null,
    val terminalFontSp: Int = 13,
    val wakeLock: Boolean = false,
    /** Write short pointers to the HaniyaX SDK into agents' instruction files (CLAUDE.md / AGENTS.md). */
    val agentSdkHints: Boolean = true,
    /** Extra keys rows as JSON (see terminal/ExtraKeys.kt). null = default. */
    val extraKeys: String? = null,
    /** Paste longer than this many chars/lines shows a preview first. */
    val pastePreviewChars: Int = 400,
    val pastePreviewLines: Int = 3,
    /** Bring the compose bar up by default when the keyboard opens. */
    val composeBarDefault: Boolean = false,
    val onboardingDone: Boolean = false,
)

interface SettingsApi {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}
