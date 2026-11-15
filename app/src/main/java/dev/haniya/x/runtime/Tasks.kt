package dev.haniya.x.runtime

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Interactive terminal tasks (pty sessions): plain shells and agents.
 * Semantics (must be preserved by every UI):
 *   - leaving a page / locking: nothing happens, process keeps running.
 *   - closeTab(id): hides the tab only; the task keeps running and stays in [TasksApi.tasks].
 *   - terminate(id): ends the process (SIGHUP→SIGTERM→SIGKILL); record stays with status Exited.
 *   - If the app process died, persisted records that were Running are restored as KilledBySystem.
 * CONTRACT — owned by runtime.
 */
@Serializable
enum class TaskKind { Shell, Agent }

@Serializable
enum class TaskStatus { Running, Exited, KilledBySystem }

@Serializable
data class TaskRecord(
    val id: String,
    val kind: TaskKind,
    val title: String,
    /** Guest working directory. */
    val cwd: String,
    /** Guest command run in a login shell; null = interactive login shell. */
    val command: String? = null,
    /** e.g. "claude", "codex", "opencode" for agent tasks. */
    val agentId: String? = null,
    /** Command that resumes this task's conversation after the process is gone (agent-specific). */
    val resumeCommand: String? = null,
    val startedAt: Long,
    val status: TaskStatus = TaskStatus.Running,
    val exitCode: Int? = null,
    /** Whether the task currently has an open tab in the terminal screen. */
    val tabOpen: Boolean = true,
    /** Last terminal title reported by the program (OSC 0/2), for display. */
    val lastTitle: String? = null,
)

data class TaskSpec(
    val kind: TaskKind,
    val title: String,
    val cwd: String,
    val command: String? = null,
    val agentId: String? = null,
    val resumeCommand: String? = null,
    val extraEnv: Map<String, String> = emptyMap(),
)

interface TasksApi {
    /** All known tasks (running first, then most recent). */
    val tasks: StateFlow<List<TaskRecord>>
    /** Id of the tab the terminal screen should show, or null. */
    val focused: StateFlow<String?>

    /** Starts a new pty task, opens a tab for it and focuses it. Returns task id. Requires Workspace Ready. */
    fun start(spec: TaskSpec): String
    /** Live session for a running task (null if not running in this process). */
    fun session(id: String): TerminalSession?
    fun focus(id: String)
    /** Re-opens a tab for a task (running or not). */
    fun openTab(id: String)
    fun closeTab(id: String)
    fun terminate(id: String)
    /** For a finished/killed task: start a new task with its resumeCommand (or same command). Returns new id. */
    fun resume(id: String): String
    fun restart(id: String): String
    /** Removes a non-running task record. */
    fun forget(id: String)
    /** Subscribe to screen-update ticks for a session (text changed / title / bell). */
    val sessionEvents: kotlinx.coroutines.flow.SharedFlow<SessionEvent>
}

sealed interface SessionEvent {
    val taskId: String
    data class TextChanged(override val taskId: String) : SessionEvent
    data class TitleChanged(override val taskId: String, val title: String?) : SessionEvent
    data class Finished(override val taskId: String, val exitCode: Int) : SessionEvent
    data class Bell(override val taskId: String) : SessionEvent
    data class CopyRequest(override val taskId: String, val text: String) : SessionEvent
    data class PasteRequest(override val taskId: String) : SessionEvent
}
