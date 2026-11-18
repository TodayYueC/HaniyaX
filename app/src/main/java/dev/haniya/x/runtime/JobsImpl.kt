package dev.haniya.x.runtime

import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------------------------
// Protocol (pure, unit-tested)
// ---------------------------------------------------------------------------------------------

/** One parsed output line of a job script. See runtime/Jobs.kt for the protocol. */
sealed interface JobLine {
    data class Step(val label: String) : JobLine
    /** 0..1 */
    data class Progress(val value: Float) : JobLine
    data class Error(val message: String) : JobLine
    data class Result(val text: String) : JobLine
    data class Log(val text: String) : JobLine
}

object JobProtocol {
    private const val STEP = "::step::"
    private const val PROGRESS = "::progress::"
    private const val ERROR = "::error::"
    private const val RESULT = "::result::"

    fun parse(line: String): JobLine {
        val t = line.trimStart()
        if (!t.startsWith("::")) return JobLine.Log(line)
        fun rest(prefix: String) = t.substring(prefix.length).trim()
        return when {
            t.startsWith(STEP) -> rest(STEP).let { if (it.isEmpty()) JobLine.Log(line) else JobLine.Step(it) }
            t.startsWith(PROGRESS) -> {
                val v = rest(PROGRESS).removeSuffix("%").trim().toFloatOrNull()
                if (v == null || v.isNaN()) JobLine.Log(line) else JobLine.Progress((v / 100f).coerceIn(0f, 1f))
            }
            t.startsWith(ERROR) -> rest(ERROR).let { if (it.isEmpty()) JobLine.Log(line) else JobLine.Error(it) }
            t.startsWith(RESULT) -> JobLine.Result(rest(RESULT))
            else -> JobLine.Log(line)
        }
    }
}

/** Folds protocol lines into the mutable parts of a [JobSnapshot]. Not thread-safe; callers synchronize. */
class JobAccumulator(private val maxLog: Int = 500) {
    private val steps = ArrayList<JobStep>()
    private val log = ArrayDeque<String>()
    var progress: Float? = null; private set
    var error: String? = null; private set
    var result: String? = null; private set

    /** Applies a line; returns true when something other than the log changed (worth publishing now). */
    fun apply(line: String): Boolean = when (val p = JobProtocol.parse(line)) {
        is JobLine.Step -> {
            val cur = steps.lastOrNull()
            if (cur != null && !cur.done && cur.label == p.label) false
            else {
                if (cur != null && !cur.done) steps[steps.lastIndex] = cur.copy(done = true)
                steps += JobStep(p.label, done = false)
                progress = null
                true
            }
        }
        is JobLine.Progress -> { progress = p.value; true }
        is JobLine.Error -> { error = p.message; addLog(p.message); true }
        is JobLine.Result -> { result = p.text; true }
        is JobLine.Log -> { addLog(p.text); false }
    }

    fun addLog(text: String) {
        log.addLast(text)
        while (log.size > maxLog) log.removeFirst()
    }

    /** Finalizes; returns the terminal state. */
    fun finish(exitCode: Int?, cancelled: Boolean, failure: String? = null): JobState {
        val state = when {
            cancelled -> JobState.Cancelled
            failure == null && exitCode == 0 -> JobState.Succeeded
            else -> JobState.Failed
        }
        when (state) {
            JobState.Succeeded -> {
                for (i in steps.indices) if (!steps[i].done) steps[i] = steps[i].copy(done = true)
                if (progress != null) progress = 1f
            }
            JobState.Failed -> {
                val i = steps.indexOfLast { !it.done }
                if (i >= 0) steps[i] = steps[i].copy(failed = true)
                if (error == null) error = failure ?: defaultError(exitCode)
            }
            else -> {}
        }
        return state
    }

    fun snapshot(id: String, title: String, state: JobState, exitCode: Int?) = JobSnapshot(
        id = id, title = title, state = state, steps = steps.toList(), progress = progress,
        log = log.toList(), error = error, result = result, exitCode = exitCode,
    )

    companion object {
        fun defaultError(exitCode: Int?): String = when (exitCode) {
            null -> "执行失败"
            124 -> "执行超时"
            137, -9 -> "进程被系统中止（退出码 137）"
            127 -> "找不到命令（退出码 127）"
            else -> "执行失败（退出码 $exitCode）"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------------------------

class JobsImpl : JobsApi {
    private class Entry(val id: String, val key: String?, val title: String) {
        val flow = MutableStateFlow<JobSnapshot?>(null)
        val acc = JobAccumulator(LOG_KEEP)
        @Volatile var process: Process? = null
        @Volatile var cancelled = false
        var lastEmit = 0L      // guarded by this
        var dirty = false      // guarded by this
        var finished = false   // guarded by this
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val counter = AtomicLong(0)
    private val _jobs = MutableStateFlow<List<JobSnapshot>>(emptyList())
    override val jobs: StateFlow<List<JobSnapshot>> = _jobs.asStateFlow()

    override fun start(title: String, script: String, key: String?, cwd: String, extraEnv: Map<String, String>): String {
        val entry = synchronized(lock) {
            if (key != null) {
                entries.values.firstOrNull { it.key == key && it.flow.value?.state == JobState.Running }?.let { return it.id }
            }
            val id = "job-${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}"
            Entry(id, key, title).also {
                it.flow.value = it.acc.snapshot(id, title, JobState.Running, null)
                entries[id] = it
                trim()
            }
        }
        publishList()
        App.scope.launch(Dispatchers.IO) { run(entry, script, cwd, extraEnv) }
        // Flushes throttled log lines while the job is quiet.
        App.scope.launch {
            while (true) {
                delay(300)
                val (done, dirty) = synchronized(entry) { entry.finished to entry.dirty }
                if (done) break
                if (dirty) emit(entry, null, null, force = true)
            }
        }
        return entry.id
    }

    override fun get(id: String): StateFlow<JobSnapshot?> =
        synchronized(lock) { entries[id]?.flow?.asStateFlow() } ?: MutableStateFlow<JobSnapshot?>(null).asStateFlow()

    override fun runningForKey(key: String): JobSnapshot? = synchronized(lock) {
        entries.values.firstOrNull { it.key == key && it.flow.value?.state == JobState.Running }?.flow?.value
    }

    override fun cancel(id: String) {
        val e = synchronized(lock) { entries[id] } ?: return
        if (e.flow.value?.state != JobState.Running) return
        e.cancelled = true
        val p = e.process ?: return
        p.destroy()
        App.scope.launch(Dispatchers.IO) {
            delay(1500)
            if (p.isAlive) p.destroyForcibly()
        }
    }

    private fun run(e: Entry, script: String, cwd: String, extraEnv: Map<String, String>) {
        var exit: Int? = null
        var failure: String? = null
        try {
            if (!Paths.host("/usr/bin/bash").isFile) {
                failure = "工作区尚未创建，请先完成工作区安装"
                return
            }
            if (e.cancelled) return
            val spec = Runtime.workspace.launch(listOf("/bin/bash", "-lc", script), cwd, extraEnv)
            val p = spec.toProcessBuilder().redirectErrorStream(true).start()
            e.process = p
            if (e.cancelled) p.destroy()
            runCatching { p.outputStream.close() }
            val splitter = LineSplitter { line ->
                val important = synchronized(e) { e.acc.apply(line) }
                emit(e, null, null, force = important)
            }
            try {
                InputStreamReader(p.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        splitter.feed(buf, n)
                    }
                }
            } catch (_: IOException) {
                // Stream closed by destroy(); the exit code tells what happened.
            }
            splitter.finish()
            exit = p.waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "job ${e.id} failed", t)
            failure = "无法启动：${t.message ?: t.javaClass.simpleName}"
        } finally {
            val state = synchronized(e) { e.acc.finish(exit, e.cancelled, failure) }
            e.process = null
            emit(e, state, exit, force = true)
            if (state == JobState.Failed) Log.i(TAG, "job ${e.id} (${e.title}) failed: exit=$exit")
        }
    }

    /** Publishes the entry's snapshot. [final] = terminal state (null while running); finals always win. */
    private fun emit(e: Entry, final: JobState?, exit: Int?, force: Boolean) {
        synchronized(e) {
            if (e.finished) return
            val now = System.currentTimeMillis()
            if (final == null && !force && now - e.lastEmit < EMIT_INTERVAL_MS) { e.dirty = true; return }
            e.dirty = false
            e.lastEmit = now
            if (final != null) e.finished = true
            e.flow.value = e.acc.snapshot(e.id, e.title, final ?: JobState.Running, exit)
        }
        publishList()
    }

    private fun publishList() {
        _jobs.value = synchronized(lock) { entries.values.mapNotNull { it.flow.value }.asReversed().toList() }
    }

    /** Keeps memory bounded: drops the oldest finished jobs beyond [MAX_JOBS]. */
    private fun trim() {
        if (entries.size <= MAX_JOBS) return
        val it = entries.values.iterator()
        while (entries.size > MAX_JOBS && it.hasNext()) {
            val e = it.next()
            if (e.flow.value?.state != JobState.Running) it.remove()
        }
    }

    companion object {
        private const val TAG = "HaniyaX.Jobs"
        private const val LOG_KEEP = 500
        private const val MAX_JOBS = 60
        private const val EMIT_INTERVAL_MS = 120L
    }
}
