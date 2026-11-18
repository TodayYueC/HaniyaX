package dev.haniya.x.services

import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.settings.HostFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background services declared in guest ~/.haniyax/services/<name>.json. Definitions are re-read every 3 s
 * (or on [reload]); processes are started through Runtime.workspace.spawn and supervised here:
 * Starting → Running once the port accepts (or after 1.5 s without a port), health-checked every 5 s,
 * Crashed / Stopped on exit. A port that is open while we hold no process is reported as Running, unmanaged.
 */
class ServiceManager : ServicesApi {
    private class Entry(@Volatile var def: ServiceDef) {
        var process: Process? = null
        var status = ServiceStatus.Stopped
        var managed = false
        var pid: Int? = null
        var startedAt: Long? = null
        var lastExitCode: Int? = null
        var stopping = false
        var generation = 0
    }

    private val lock = Any()
    private val scanLock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var stamps: Map<String, Pair<Long, Long>> = emptyMap()

    private val _services = MutableStateFlow<List<ServiceState>>(emptyList())
    override val services: StateFlow<List<ServiceState>> = _services.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Last start/stop problem per service, in Chinese (cleared on the next successful start). */
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _invalid = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Definition files that could not be parsed: file name → reason. */
    val invalid: StateFlow<Map<String, String>> = _invalid.asStateFlow()

    private val rescan = Channel<Unit>(Channel.CONFLATED)
    /** Set by [reload]: the next scan re-reads every file even if mtimes and sizes look unchanged. */
    private val forceNext = AtomicBoolean(false)
    private val autostarted = AtomicBoolean(false)

    init {
        App.scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { scanDefs(force = forceNext.getAndSet(false)) } catch (e: Exception) { Log.w(TAG, "读取服务定义失败", e) }
                withTimeoutOrNull(SCAN_INTERVAL_MS) { rescan.receive() }
            }
        }
        App.scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                try { healthTick() } catch (e: Exception) { Log.w(TAG, "健康检查失败", e) }
            }
        }
        App.scope.launch {
            Runtime.workspace.state.first { it is WorkspaceState.Ready }
            withContext(Dispatchers.IO) {
                scanDefs(force = true)
                healthTick()
                autostart()
            }
        }
    }

    // ---- definitions ---------------------------------------------------------------------------------------

    private fun scanDefs(force: Boolean = false) = synchronized(scanLock) {
        val dir = Paths.servicesHost
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
        val now = files.associate { it.name.removeSuffix(".json") to (it.lastModified() to it.length()) }
        if (!force && now == stamps) return@synchronized
        stamps = now
        val defs = LinkedHashMap<String, ServiceDef>()
        val bad = LinkedHashMap<String, String>()
        for (f in files) {
            val stem = f.name.removeSuffix(".json")
            val text = try { f.readText() } catch (e: Exception) { bad[f.name] = "无法读取：${e.message}"; continue }
            ServiceDefs.parse(stem, text)
                .onSuccess { defs[it.name] = it }
                .onFailure {
                    val msg = it.message ?: it.javaClass.simpleName
                    bad[f.name] = msg
                    Log.w(TAG, "跳过无效的服务定义 ${f.name}：$msg")
                }
        }
        _invalid.value = bad
        val removed = mutableListOf<Entry>()
        synchronized(lock) {
            for (name in entries.keys.toList()) if (name !in defs) removed += entries.remove(name)!!
            for ((name, def) in defs) {
                val e = entries[name]
                if (e == null) entries[name] = Entry(def) else e.def = def
            }
        }
        removed.forEach { e -> e.process?.let { p -> synchronized(lock) { e.stopping = true }; terminate(p) } }
        publish()
    }

    override fun define(def: ServiceDef) {
        ServiceDefs.validate(def)?.let { throw IllegalArgumentException(it) }
        synchronized(scanLock) {
            val f = File(Paths.servicesHost, "${def.name}.json")
            HostFiles.writeAtomic(f, ServiceDefs.encode(def))
            synchronized(lock) {
                val e = entries[def.name]
                if (e == null) entries[def.name] = Entry(def) else e.def = def
            }
            stamps = stamps + (def.name to (f.lastModified() to f.length()))
        }
        publish()
    }

    override fun remove(name: String) {
        val e = synchronized(lock) { entries[name] }
        e?.process?.let { p -> synchronized(lock) { e.stopping = true }; terminate(p) }
        synchronized(scanLock) {
            File(Paths.servicesHost, "$name.json").delete()
            synchronized(lock) { entries.remove(name) }
            stamps = stamps - name
        }
        clearError(name)
        publish()
    }

    override fun reload() {
        forceNext.set(true)
        rescan.trySend(Unit)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    override fun start(name: String) {
        App.scope.launch(Dispatchers.IO) { startInternal(name) }
    }

    override fun stop(name: String) {
        val e = synchronized(lock) { entries[name] } ?: return
        val p = synchronized(lock) { e.stopping = true; e.process }
        if (p == null) {
            if (e.status == ServiceStatus.Running && !e.managed) setError(name, "这个服务不是 HaniyaX 启动的，请在启动它的终端里结束")
            return
        }
        terminate(p)
    }

    override fun restart(name: String) {
        App.scope.launch(Dispatchers.IO) {
            val e = synchronized(lock) { entries[name] } ?: return@launch
            val p = synchronized(lock) { e.stopping = true; e.process }
            if (p != null) {
                terminate(p)
                withTimeoutOrNull(STOP_GRACE_MS + 3_000) { runInterruptible { p.waitFor() } }
                // Let the exit watcher record the exit before starting again.
                withTimeoutOrNull(2_000) { while (synchronized(lock) { e.process } === p) delay(50) }
            }
            startInternal(name)
        }
    }

    private fun terminate(p: Process) {
        App.scope.launch(Dispatchers.IO) {
            runCatching { p.destroy() }
            val exited = withTimeoutOrNull(STOP_GRACE_MS) { runInterruptible { p.waitFor() } }
            if (exited == null) runCatching { p.destroyForcibly() }
        }
    }

    private suspend fun startInternal(name: String): Boolean {
        val e = synchronized(lock) { entries[name] } ?: run { setError(name, "找不到服务 $name"); return false }
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) { setError(name, "工作区还没有就绪"); return false }
        if (synchronized(lock) { e.process?.isAlive == true }) return true
        val def = e.def
        val port = def.port
        if (port != null && portOpen(port)) {
            synchronized(lock) { e.status = ServiceStatus.Running; e.managed = false }
            publish()
            setError(name, "端口 $port 已被其他程序占用，没有重复启动")
            return false
        }
        if (!Paths.host(def.cwd).isDirectory) { setError(name, "目录 ${def.cwd} 不存在"); return false }

        val log = Paths.host(ServiceDefs.logPath(def))
        try {
            log.parentFile?.mkdirs()
            if (log.length() > LOG_ROTATE_BYTES) {
                val old = File(log.path + ".1"); old.delete(); log.renameTo(old)
            }
            log.appendText("\n[haniyax] ${stamp()} 启动：${def.command}\n")
        } catch (ex: Exception) {
            Log.w(TAG, "无法写入日志 ${log.path}", ex)
        }

        val proc = try {
            Runtime.workspace.spawn(ServiceDefs.launchScript(def.command), def.cwd, log, def.env)
        } catch (ex: Throwable) {
            synchronized(lock) { e.status = ServiceStatus.Crashed; e.managed = false }
            publish()
            setError(name, "启动失败：${ex.message ?: ex.javaClass.simpleName}")
            return false
        }
        val gen = synchronized(lock) {
            e.process = proc
            e.managed = true
            e.status = ServiceStatus.Starting
            e.startedAt = System.currentTimeMillis()
            e.pid = pidOf(proc)
            e.stopping = false
            e.lastExitCode = null
            ++e.generation
        }
        clearError(name)
        publish()
        App.scope.launch(Dispatchers.IO) { watchExit(e, proc, gen, log) }
        App.scope.launch(Dispatchers.IO) { awaitReady(e, proc, gen) }
        return true
    }

    private suspend fun watchExit(e: Entry, proc: Process, gen: Int, log: File) {
        val code = try { runInterruptible { proc.waitFor() } } catch (ex: Exception) { -1 }
        var crashed = false
        synchronized(lock) {
            if (e.generation != gen || e.process !== proc) return
            e.process = null
            e.pid = null
            e.managed = false
            e.lastExitCode = code
            crashed = !e.stopping && code != 0
            e.status = if (crashed) ServiceStatus.Crashed else ServiceStatus.Stopped
            e.startedAt = null
            e.stopping = false
        }
        runCatching { log.appendText("[haniyax] ${stamp()} 进程已退出，退出码 $code\n") }
        publish()
    }

    private suspend fun awaitReady(e: Entry, proc: Process, gen: Int) {
        fun current() = synchronized(lock) { e.generation == gen && e.process === proc && e.status == ServiceStatus.Starting }
        val port = e.def.port
        if (port == null) {
            delay(NO_PORT_READY_MS)
            if (proc.isAlive) synchronized(lock) { if (current()) e.status = ServiceStatus.Running }
            publish()
            return
        }
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (proc.isAlive && current()) {
            if (portOpen(port)) {
                synchronized(lock) { if (current()) e.status = ServiceStatus.Running }
                publish()
                return
            }
            if (System.currentTimeMillis() > deadline) {
                synchronized(lock) { if (current()) e.status = ServiceStatus.Unhealthy }
                publish()
                return
            }
            delay(400)
        }
    }

    private fun autostart() {
        if (!autostarted.compareAndSet(false, true)) return
        val names = synchronized(lock) {
            entries.values.filter { it.def.autostart && it.process == null && it.status != ServiceStatus.Running }.map { it.def.name }
        }
        names.forEach { start(it) }
    }

    // ---- health --------------------------------------------------------------------------------------------

    private fun healthTick() {
        val snapshot = synchronized(lock) { entries.values.toList() }
        var changed = false
        for (e in snapshot) {
            val (def, proc, status, managed) = synchronized(lock) { Quad(e.def, e.process, e.status, e.managed) }
            val port = def.port
            if (proc != null) {
                if (!proc.isAlive || port == null) continue
                if (status != ServiceStatus.Running && status != ServiceStatus.Unhealthy) continue
                val next = if (probe(def, port)) ServiceStatus.Running else ServiceStatus.Unhealthy
                synchronized(lock) {
                    if (e.process === proc && e.status != next && (e.status == ServiceStatus.Running || e.status == ServiceStatus.Unhealthy)) {
                        e.status = next; changed = true
                    }
                }
            } else if (port != null) {
                val open = portOpen(port)
                val next = when {
                    open -> if (def.healthPath == null || httpOk(port, def.healthPath)) ServiceStatus.Running else ServiceStatus.Unhealthy
                    !managed && (status == ServiceStatus.Running || status == ServiceStatus.Unhealthy) -> ServiceStatus.Stopped
                    else -> status
                }
                synchronized(lock) {
                    if (e.process == null && (e.status != next || (open && e.managed))) {
                        e.status = next
                        if (open) { e.managed = false; e.startedAt = null; e.pid = null }
                        changed = true
                    }
                }
            }
        }
        if (changed) publish()
    }

    private data class Quad(val def: ServiceDef, val proc: Process?, val status: ServiceStatus, val managed: Boolean)

    private fun probe(def: ServiceDef, port: Int): Boolean {
        val path = def.healthPath
        return if (path != null) httpOk(port, path) else portOpen(port)
    }

    private fun httpOk(port: Int, path: String): Boolean = try {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_500
        conn.readTimeout = 2_500
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        try { conn.responseCode in 1..499 } finally { conn.disconnect() }
    } catch (e: Exception) {
        false
    }

    // ---- queries -------------------------------------------------------------------------------------------

    override suspend fun tail(name: String, lines: Int): List<String> {
        val def = synchronized(lock) { entries[name]?.def } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                LogTail.read(Paths.host(ServiceDefs.logPath(def)), lines).map(LogText::clean)
            } catch (e: Exception) {
                Log.w(TAG, "读取日志失败", e)
                emptyList()
            }
        }
    }

    override suspend fun awaitPort(name: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val def = synchronized(lock) { entries[name]?.def }
        val port = def?.port
        withTimeoutOrNull(timeoutMs) {
            if (port == null) {
                services.first { list -> list.any { it.def.name == name && it.status == ServiceStatus.Running } }
            } else {
                while (!portOpen(port)) delay(300)
            }
            true
        } ?: false
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun publish() {
        val list = synchronized(lock) {
            entries.values.map { e ->
                ServiceState(
                    def = e.def, status = e.status, managed = e.managed, pid = e.pid, startedAt = e.startedAt,
                    address = ServiceDefs.address(e.def), lastExitCode = e.lastExitCode,
                )
            }
        }.sortedBy { it.def.name }
        _services.value = list
    }

    private fun setError(name: String, message: String) = _errors.update { it + (name to message) }
    private fun clearError(name: String) = _errors.update { it - name }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    companion object {
        private const val TAG = "HxServices"
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val HEALTH_INTERVAL_MS = 5_000L
        private const val NO_PORT_READY_MS = 1_500L
        private const val START_TIMEOUT_MS = 90_000L
        private const val STOP_GRACE_MS = 3_000L
        private const val LOG_ROTATE_BYTES = 8L shl 20

        fun portOpen(port: Int, timeoutMs: Int = 800): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
        } catch (e: Exception) {
            false
        }

        /** Errors flow of the registered implementation (empty for any other ServicesApi). */
        fun errorsOf(api: ServicesApi): StateFlow<Map<String, String>> =
            (api as? ServiceManager)?.errors ?: MutableStateFlow(emptyMap())

        /** Host pid of the proot process (best effort: Process.pid() on newer runtimes, else the private field). */
        private fun pidOf(p: Process): Int? = runCatching {
            (p.javaClass.getMethod("pid").invoke(p) as Number).toInt()
        }.recoverCatching {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(p)
        }.getOrNull()
    }
}
