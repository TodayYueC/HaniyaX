package dev.haniya.x.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Home-screen card plugins living in ~/.haniyax/plugins/<id>/plugin.json (guest).
 * CONTRACT — owned by the plugins module. Manifest schema lives in plugins/Manifest.kt (plugins module).
 */
enum class PluginTrust {
    /** Only reads files/ports/http/process state — runs without confirmation. */
    DisplayOnly,
    /** Runs commands (data sources or actions) — the user must approve the exact commands first. */
    RunsCommands,
}

data class PluginSummary(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val icon: String,
    val trust: PluginTrust,
    /** RunsCommands plugins: true when the current command set was approved by the user in the app UI. */
    val approved: Boolean,
    /** Validation errors; a plugin with errors renders as an error card. */
    val errors: List<String>,
)

interface PluginsApi {
    val plugins: StateFlow<List<PluginSummary>>
    fun reload()
    /** Renders the card for [id] (used by the home screen inside its module list). */
    @Composable fun Card(id: String, modifier: Modifier)
    /** Approves the plugin's current command set (UI only, never callable from the guest). */
    fun approve(id: String)
    fun revoke(id: String)
    fun remove(id: String)
}
