package dev.haniya.x.ui

import androidx.compose.runtime.mutableStateListOf

/** App destinations. CONTRACT — owned by the shell (MainActivity). */
sealed interface Route {
    data object Home : Route
    /** Terminal screen; focuses [taskId] when given. */
    data class Terminal(val taskId: String? = null) : Route
    data object Agents : Route
    /** Provider/model management, optionally preselecting an agent id ("claude", "codex", "opencode", "dsh"). */
    data class Providers(val agentId: String? = null) : Route
    data object Dsh : Route
    data object Settings : Route
    /** Plugin card details / permissions / editor for plugin [pluginId]. */
    data class Plugin(val pluginId: String) : Route
    /** Service detail: status, address, logs, start/stop. */
    data class Service(val name: String) : Route
}

/** Minimal back stack. Call from the main thread only. */
object Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val current: Route get() = stack.last()
    fun go(route: Route) { if (stack.last() != route) stack.add(route) }
    /** Replaces the whole stack with Home + [route]. */
    fun reset(route: Route = Route.Home) { stack.clear(); stack.add(Route.Home); if (route != Route.Home) stack.add(route) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}
