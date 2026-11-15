package dev.haniya.x.home

import kotlinx.serialization.Serializable

/**
 * Home module ids: "projects", "tasks", "services" (built-in) and "plugin:<id>" for plugin cards.
 * The three entry tiles (terminal, agents, DSH) are always shown and are not modules.
 * CONTRACT — owned by the home module; persisted in Paths.state/home.json.
 */
@Serializable
data class HomeModule(val id: String, val hidden: Boolean = false, val pinned: Boolean = false)

/** What to open when the app launches. */
@Serializable
sealed interface StartTarget {
    @Serializable @kotlinx.serialization.SerialName("home") data object Home : StartTarget
    @Serializable @kotlinx.serialization.SerialName("project") data class Project(val path: String, val agentId: String? = null) : StartTarget
    @Serializable @kotlinx.serialization.SerialName("task") data class Task(val taskId: String) : StartTarget
}

@Serializable
data class HomeLayout(
    val modules: List<HomeModule> = listOf(HomeModule("projects"), HomeModule("tasks"), HomeModule("services")),
    val start: StartTarget = StartTarget.Home,
)
