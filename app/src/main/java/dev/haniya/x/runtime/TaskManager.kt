package dev.haniya.x.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import dev.haniya.x.terminal.TerminalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Running-task summary observed by [WorkspaceService]. */
data class TaskCounts(val running: Int = 0, val runningAgents: Int = 0, val latestRunningId: String? = null)

/** Pure helpers for task records (unit-tested). */
object TaskRecords {
    /** Running first, then most recently started. */
    val ORDER: Comparator<TaskRecord> = compareBy<TaskRecord> { if (it.status == TaskStatus.Running) 0 else 1 }
        .thenByDescending { it.startedAt }
        .thenBy { it.id }

    fun sorted(records: Collection<TaskRecord>): List<TaskRecord> = records.sortedWith(ORDER)

    /** After an app restart nothing is running in this process: Running records become KilledBySystem. */
    fun restore(records: List<TaskRecord>): List<TaskRecord> =
        records.map { if (it.status == TaskStatus.Running) it.copy(status = TaskStatus.KilledBySystem) else it }

    fun encode(records: List<TaskRecord>): String = AppJson.encodeToString(ListSerializer(TaskRecord.serializer()), records)

    fun decode(text: String?): List<TaskRecord> =
        if (text.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString(ListSerializer(TaskRecord.serializer()), text) }.getOrDefault(emptyList())

    fun counts(records: Collection<TaskRecord>): TaskCounts {
        val running = records.filter { it.status == TaskStatus.Running }
        return TaskCounts(
            running = running.size,
            runningAgents = running.count { it.kind == TaskKind.Agent },
            latestRunningId = running.maxByOrNull { it.startedAt }?.id,
        )
    }

    /** Tab to focus after [closedId] loses its tab: prefer a running open tab, else any open tab. */
    fun nextFocus(records: List<TaskRecord>, closedId: String): String? {
        val open = sorted(records).filter { it.tabOpen && it.id != closedId }
        return open.firstOrNull { it.status == TaskStatus.Running }?.id ?: open.firstOrNull()?.id
    }
}

/**
 * Interactive pty tasks backed by Termux [TerminalSession]s. Must be constructed on the main thread;
 * all session work happens on the main thread (TerminalSession posts its callbacks to a main-looper Handler).
 */
class TaskManager(private val context: Context) : TasksApi {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val records = LinkedHashMap<String, TaskRecord>() // guarded by lock
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val idBySession = ConcurrentHashMap<String, String>() // TerminalSession.mHandle -> task id
    private val forgetOnExit = ConcurrentHashMap.newKeySet<String>()
    private val idCounter = AtomicLong(0)
    private val stateFile get() = File(Paths.state, "tasks.json")

    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    override val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()

    private val _focused = MutableStateFlow<String?>(null)
    override val focused: StateFlow<String?> = _focused.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val sessionEvents: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _counts = MutableStateFlow(TaskCounts())
    /** Running counts; [WorkspaceService] observes this for its notification. */
    val counts: StateFlow<TaskCounts> = _counts.asStateFlow()

    init {
        val loaded = TaskRecords.restore(TaskRecords.decode(runCatching { stateFile.takeIf { it.isFile }?.readText() }.getOrNull()))
        synchronized(lock) { loaded.forEach { records[it.id] = it } }
        publish()
        startPersisting()
        writeNow(_tasks.value)
    }

    // ---- TasksApi ----

    override fun start(spec: TaskSpec): String {
        val id = "t-${System.currentTimeMillis().toString(36)}-${idCounter.incrementAndGet()}"
        val rec = TaskRecord(
            id = id, kind = spec.kind, title = spec.title, cwd = spec.cwd, command = spec.command,
            agentId = spec.agentId, resumeCommand = spec.resumeCommand, startedAt = System.currentTimeMillis(),
            status = TaskStatus.Running, tabOpen = true,
        )
        mutate { it[id] = rec }
        _focused.value = id
        onMain { createSession(id, spec) }
        WorkspaceService.ensureStarted(context)
        return id
    }

    override fun session(id: String): TerminalSession? = sessions[id]

    override fun focus(id: String) {
        if (synchronized(lock) { records[id] } == null) return
        update(id) { if (it.tabOpen) it else it.copy(tabOpen = true) }
        _focused.value = id
    }

    override fun openTab(id: String) = focus(id)

    override fun closeTab(id: String) {
        update(id) { it.copy(tabOpen = false) }
        if (_focused.value == id) _focused.value = TaskRecords.nextFocus(_tasks.value, id)
    }

    override fun terminate(id: String) = onMain {
        val s = sessions[id] ?: return@onMain
        if (!s.isRunning) return@onMain
        val pid = s.pid
        if (pid <= 0) { s.finishIfRunning(); return@onMain }
        signalGroup(pid, OsConstants.SIGHUP)
        main.postDelayed({
            if (s.isRunning && s.pid == pid) {
                signalGroup(pid, OsConstants.SIGTERM)
                main.postDelayed({
                    if (s.isRunning && s.pid == pid) {
                        signalGroup(pid, OsConstants.SIGKILL)
                        s.finishIfRunning()
                    }
                }, 3000)
            }
        }, 1500)
    }

    override fun resume(id: String): String = relaunch(id, useResume = true)

    override fun restart(id: String): String = relaunch(id, useResume = false)

    override fun forget(id: String) {
        val rec = synchronized(lock) { records[id] } ?: return
        if (rec.status == TaskStatus.Running && sessions[id]?.isRunning != false) return
        remove(id)
    }

    // ---- internals ----

    private fun relaunch(id: String, useResume: Boolean): String {
        val old = synchronized(lock) { records[id] } ?: return id
        val live = sessions[id]?.isRunning == true
        if (useResume && live) { focus(id); return id }
        val command = if (useResume) old.resumeCommand ?: old.command else old.command
        val newId = start(
            TaskSpec(
                kind = old.kind, title = old.title, cwd = old.cwd, command = command,
                agentId = old.agentId, resumeCommand = old.resumeCommand,
            ),
        )
        if (live) {
            // Restarting a live task: end the old process, drop its record once it has exited.
            forgetOnExit += id
            terminate(id)
        } else {
            remove(id)
        }
        _focused.value = newId
        return newId
    }

    private fun remove(id: String) {
        mutate { it.remove(id) }
        sessions.remove(id)?.let { idBySession.remove(it.mHandle) }
        forgetOnExit.remove(id)
        if (_focused.value == id) _focused.value = TaskRecords.nextFocus(_tasks.value, id)
    }

    private fun createSession(id: String, spec: TaskSpec) {
        if (synchronized(lock) { records[id] } == null) return // forgotten before main got to it
        val env = (runCatching { TerminalTheme.env() }.getOrDefault(emptyMap())) + spec.extraEnv
        try {
            val launch = Runtime.workspace.shellLaunch(spec.cwd, spec.command, env)
            val s = TerminalSession(launch.executable, launch.hostCwd, launch.args, launch.env, TRANSCRIPT_ROWS, client)
            s.mSessionName = spec.title
            sessions[id] = s
            idBySession[s.mHandle] = id
            // Starts the process now; the TerminalView resizes the pty once it is laid out.
            s.updateSize(80, 24, 12, 24)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start task $id", t)
            sessions.remove(id)?.let { idBySession.remove(it.mHandle) }
            update(id) { it.copy(status = TaskStatus.Exited, exitCode = 127, lastTitle = "无法启动：${t.message ?: t.javaClass.simpleName}") }
            _events.tryEmit(SessionEvent.Finished(id, 127))
        }
    }

    private fun signalGroup(pid: Int, sig: Int) {
        try {
            Os.kill(-pid, sig) // the child called setsid(): pid is also its process group id
        } catch (_: ErrnoException) {
            try { Os.kill(pid, sig) } catch (e: ErrnoException) { Log.w(TAG, "kill($pid, $sig): ${e.message}") }
        }
    }

    private fun idOf(s: TerminalSession): String? = idBySession[s.mHandle]

    private inline fun mutate(block: (LinkedHashMap<String, TaskRecord>) -> Unit) {
        synchronized(lock) { block(records) }
        publish()
    }

    private fun update(id: String, transform: (TaskRecord) -> TaskRecord) {
        var changed = false
        synchronized(lock) {
            val cur = records[id] ?: return
            val next = transform(cur)
            if (next != cur) { records[id] = next; changed = true }
        }
        if (changed) publish()
    }

    /** Publishes under the lock so concurrent mutations can never publish an older list after a newer one. */
    private fun publish() {
        synchronized(lock) {
            val list = TaskRecords.sorted(records.values)
            _tasks.value = list
            _counts.value = TaskRecords.counts(list)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPersisting() {
        val io = Dispatchers.IO.limitedParallelism(1)
        // StateFlow conflates: bursts of changes collapse into one write of the latest list.
        App.scope.launch(io) { _tasks.drop(1).collect { writeNow(it) } }
    }

    private fun writeNow(list: List<TaskRecord>) {
        try {
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(TaskRecords.encode(list))
            if (!tmp.renameTo(stateFile)) { stateFile.writeText(TaskRecords.encode(list)); tmp.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "persist tasks failed", t)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            idOf(changedSession)?.let { _events.tryEmit(SessionEvent.TextChanged(it)) }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val id = idOf(changedSession) ?: return
            val title = changedSession.title
            update(id) { it.copy(lastTitle = title) }
            _events.tryEmit(SessionEvent.TitleChanged(id, title))
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val id = idOf(finishedSession) ?: return
            val code = finishedSession.exitStatus
            if (forgetOnExit.remove(id)) {
                remove(id)
            } else {
                update(id) { it.copy(status = TaskStatus.Exited, exitCode = code) }
            }
            _events.tryEmit(SessionEvent.Finished(id, code))
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val id = idOf(session) ?: return
            if (text.isNullOrEmpty()) return
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("HaniyaX", text))
            }
            _events.tryEmit(SessionEvent.CopyRequest(id, text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val id = session?.let(::idOf) ?: _focused.value ?: return
            _events.tryEmit(SessionEvent.PasteRequest(id))
        }

        override fun onBell(session: TerminalSession) {
            idOf(session)?.let { _events.tryEmit(SessionEvent.Bell(it)) }
        }

        override fun onColorsChanged(session: TerminalSession) {
            idOf(session)?.let { _events.tryEmit(SessionEvent.TextChanged(it)) }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            _focused.value?.let { _events.tryEmit(SessionEvent.TextChanged(it)) }
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            idOf(session)?.let { Log.i(TAG, "task $it started, pid $pid") }
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    companion object {
        private const val TAG = "HaniyaX.Tasks"
        private const val TRANSCRIPT_ROWS = 5000
    }
}
