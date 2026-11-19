package dev.haniya.x.home

import kotlinx.serialization.Serializable

/**
 * Home module ids: "projects", "tasks" (built-in), addable cards "services", "dsh", "terminal", "files",
 * "agent:<agentId>", "project:<guest path>", and "plugin:<id>" for plugin cards.
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
    val modules: List<HomeModule> = listOf(HomeModule("projects"), HomeModule("tasks")),
    val start: StartTarget = StartTarget.Home,
    /** Layout format: 1 = services shown by default (before the bottom bar), 2 = services is an opt-in card. */
    val version: Int = 1,
)
