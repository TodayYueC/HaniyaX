package dev.haniya.x.home

import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.settings.JsonStateFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home layout (module order, hidden/pinned flags, start target) persisted in Paths.state/home.json.
 * Keeps plugin modules in sync with App.plugins: new plugins are appended, plugins removed from disk disappear.
 */
object HomeStore {
    private val file = JsonStateFile(File(Paths.state, "home.json"), HomeLayout.serializer()) { HomeLayout() }
    private val state = MutableStateFlow(HomeLayoutOps.reconcile(file.load(), emptyList()) { false })
    val layout: StateFlow<HomeLayout> = state.asStateFlow()

    init {
        file.autosave(state, state.value)
        App.scope.launch(Dispatchers.IO) {
            App.plugins.plugins.collect { list ->
                val ids = list.map { it.id }
                state.update { l -> HomeLayoutOps.reconcile(l, ids) { id -> !File(Paths.pluginsHost, id).exists() } }
            }
        }
    }

    fun update(transform: (HomeLayout) -> HomeLayout) {
        state.update { HomeLayoutOps.normalized(transform(it)) }
    }

    fun setStart(target: StartTarget) = update { it.copy(start = target) }

    /** Resets the start target to Home if it currently equals [target]. */
    fun clearStartIf(target: StartTarget) = update { if (it.start == target) it.copy(start = StartTarget.Home) else it }

    fun move(id: String, delta: Int, available: (String) -> Boolean) = update { HomeLayoutOps.move(it, id, delta, available) }
    fun setHidden(id: String, hidden: Boolean) = update { HomeLayoutOps.setHidden(it, id, hidden) }
    fun setPinned(id: String, pinned: Boolean) = update { HomeLayoutOps.setPinned(it, id, pinned) }
    fun add(id: String) = update { HomeLayoutOps.add(it, id) }
}
