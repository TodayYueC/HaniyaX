package dev.haniya.x.home

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.haniya.x.App
import dev.haniya.x.agents.AgentStatus
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies HomeLayout.start once per process, on the first composition after the workspace is ready.
 * If something already navigated (e.g. a notification opened a task) the start target is skipped.
 * Missing targets fall back to Home silently.
 */
object StartRouter {
    private val applied = AtomicBoolean(false)

    @Composable
    fun ApplyOnce() {
        LaunchedEffect(Unit) {
            if (!applied.compareAndSet(false, true)) return@LaunchedEffect
            try {
                apply(HomeStore.layout.value.start)
            } catch (e: Exception) {
                Log.w("HxStart", "无法打开默认入口", e)
            }
        }
    }

    private fun stillHome() = Nav.stack.size == 1 && Nav.current == Route.Home

    private suspend fun apply(target: StartTarget) {
        if (!stillHome()) return
        when (target) {
            StartTarget.Home -> Unit
            is StartTarget.Task -> {
                val rec = Runtime.tasks.tasks.value.firstOrNull { it.id == target.taskId } ?: return
                Runtime.tasks.openTab(rec.id)
                Runtime.tasks.focus(rec.id)
                Nav.go(Route.Terminal(rec.id))
            }
            is StartTarget.Project -> openProject(target.path, target.agentId)
        }
    }

    private suspend fun openProject(path: String, agentId: String?) {
        val exists = withContext(Dispatchers.IO) { Paths.host(path).isDirectory }
        if (!exists) return
        val running = Runtime.tasks.tasks.value.filter { it.status == TaskStatus.Running && TaskList.inProject(it, path) }
        val match = if (agentId != null) running.firstOrNull { it.agentId == agentId } else running.firstOrNull()
        if (match != null) {
            Runtime.tasks.openTab(match.id)
            Runtime.tasks.focus(match.id)
            Nav.go(Route.Terminal(match.id))
            return
        }
        val useAgent = agentId != null && agentInstalled(agentId)
        val id = if (agentId != null && useAgent) {
            val sessions = withTimeoutOrNull(8_000) { App.agents.sessions(agentId, path) } ?: emptyList()
            if (!stillHome()) return
            App.agents.launch(agentId, path, resume = if (sessions.isNotEmpty()) "" else null)
        } else {
            if (!stillHome()) return
            Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = Fmt.folderName(path), cwd = path))
        }
        App.projects.touch(path, if (useAgent) agentId else null)
        Nav.go(Route.Terminal(id))
    }

    /** Waits briefly for the agents module to settle the status of [agentId]. */
    private suspend fun agentInstalled(agentId: String): Boolean {
        App.agents.status.value[agentId]?.let { if (it is AgentStatus.Installed) return true }
        App.agents.refresh(agentId)
        val settled = withTimeoutOrNull(6_000) {
            App.agents.status.first { m ->
                val s = m[agentId]
                s is AgentStatus.Installed || s is AgentStatus.NotInstalled || s is AgentStatus.Error
            }
        }
        return settled?.get(agentId) is AgentStatus.Installed
    }
}
