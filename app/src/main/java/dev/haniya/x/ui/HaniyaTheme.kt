package dev.haniya.x.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.haniya.relief.theme.ReliefPalette
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App

/** Resolves the active palette: user choice, else system light → Platinum, dark → Obsidian. */
@Composable
fun activePalette(): ReliefPalette {
    val s by App.settings.settings.collectAsState()
    return s.palette ?: if (isSystemInDarkTheme()) ReliefPalette.OBSIDIAN else ReliefPalette.PLATINUM
}

@Composable
fun HaniyaTheme(content: @Composable () -> Unit) {
    ReliefTheme(activePalette(), content = content)
}
