package dev.haniya.x.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------------------------
// Pure model (unit-tested)
// ---------------------------------------------------------------------------------------------

/** Installation steps in order. [id] is what gets persisted. */
enum class InstallStep(val id: String, val label: String) {
    Runtime("runtime", "解压运行时"),
    Rootfs("rootfs", "解压 Debian 系统"),
    Configure("configure", "配置网络与基础环境"),
    Packages("packages", "安装基础软件"),
    Sdk("sdk", "安装 HaniyaX 工具");

    companion object {
        fun byId(id: String): InstallStep? = entries.firstOrNull { it.id == id }
        fun byLabel(label: String): InstallStep? = entries.firstOrNull { it.label == label }
    }
}

/** Persisted in Paths.state/workspace.json. */
@Serializable
data class WorkspaceRecord(
    val version: Int = 1,
    /** Ids of finished [InstallStep]s. */
    val done: List<String> = emptyList(),
    val installedAt: Long? = null,
) {
    fun isDone(step: InstallStep) = step.id in done
    fun markDone(step: InstallStep) = if (isDone(step)) this else copy(done = done + step.id)
    fun unmark(steps: Collection<InstallStep>) = copy(done = done.filter { id -> steps.none { it.id == id } })
}

object WorkspaceSteps {
    fun encode(record: WorkspaceRecord): String = AppJson.encodeToString(WorkspaceRecord.serializer(), record)

    /** Corrupt or unknown content decodes to an empty record (= not installed), never throws. */
    fun decode(text: String?): WorkspaceRecord =
        if (text.isNullOrBlank()) WorkspaceRecord()
        else runCatching { AppJson.decodeFromString(WorkspaceRecord.serializer(), text) }.getOrDefault(WorkspaceRecord())

    fun allDone(record: WorkspaceRecord): Boolean = InstallStep.entries.all(record::isDone)

    /**
     * Steps that still have to run: everything from the first step that is not marked done, or whose
     * artifact is missing (e.g. the rootfs was deleted), to the end. Later steps depend on earlier ones.
     */
    fun pending(record: WorkspaceRecord, artifactOk: (InstallStep) -> Boolean): List<InstallStep> {
        val first = InstallStep.entries.indexOfFirst { !record.isDone(it) || !artifactOk(it) }
        return if (first < 0) emptyList() else InstallStep.entries.drop(first)
    }
}

/** Parses `apt-get -o APT::Status-Fd=3 3>&1` lines (fd 1 must not be used: it breaks debconf maintainer scripts) into an overall progress (0..1) across update + install. */
class AptProgress {
    enum class Phase { Update, Install }

    var phase: Phase = Phase.Update
        private set

    /** Returns true when [line] is a protocol line (and should be hidden from the log). */
    fun isProtocol(line: String): Boolean =
        line.startsWith("dlstatus:") || line.startsWith("pmstatus:") || line.startsWith("::hx-phase::") ||
            line.startsWith("pmconffile:") || line.startsWith("pmerror:") || line.startsWith("media-change:")

    /** Feeds one line; returns the new overall progress if the line carried one. */
    fun feed(line: String): Float? {
        if (line.startsWith("::hx-phase::")) {
            phase = if (line.substringAfter("::hx-phase::").trim() == "install") Phase.Install else Phase.Update
            return if (phase == Phase.Install) UPDATE_SHARE else 0f
        }
        val kind = when {
            line.startsWith("dlstatus:") -> 0
            line.startsWith("pmstatus:") -> 1
            else -> return null
        }
        val pct = fields(line)?.first ?: return null
        return when {
            phase == Phase.Update -> UPDATE_SHARE * pct
            kind == 0 -> UPDATE_SHARE + DOWNLOAD_SHARE * pct
            else -> UPDATE_SHARE + DOWNLOAD_SHARE + (1f - UPDATE_SHARE - DOWNLOAD_SHARE) * pct
        }
    }

    /** Human text of a pmstatus/dlstatus line (e.g. "Unpacking curl (arm64)"), or null. */
    fun describe(line: String): String? {
        if (!line.startsWith("dlstatus:") && !line.startsWith("pmstatus:")) return null
        return fields(line)?.second
    }

    /**
     * `kind:<id or package[:arch]>:<percent>:<text>` → (percent 0..1, text). The percent is the first numeric
     * field after the id, so a package name that carries an architecture (`libc6:arm64`) still parses.
     */
    private fun fields(line: String): Pair<Float, String?>? {
        val parts = line.split(':')
        for (i in 2 until parts.size) {
            val v = parts[i].trim().toFloatOrNull() ?: continue
            if (v.isNaN()) return null
            val text = parts.drop(i + 1).joinToString(":").trim().takeIf { it.isNotEmpty() }
            return v.coerceIn(0f, 100f) / 100f to text
        }
        return null
    }

    companion object {
        const val UPDATE_SHARE = 0.15f
        const val DOWNLOAD_SHARE = 0.40f
    }
}

/** Turns raw failure evidence into a calm Chinese message with the likely cause. */
object FailureClassifier {
    fun cause(log: List<String>, error: Throwable?, exitCode: Int?): String? {
        val text = (log.takeLast(80) + listOfNotNull(error?.message, error?.cause?.message)).joinToString("\n")
        val t = text.lowercase()
        return when {
            "no space left" in t || "enospc" in t || "存储空间不足" in text -> "存储空间不足"
            listOf(
                "temporary failure resolving", "could not resolve", "network is unreachable", "connection timed out",
                "connection refused", "unable to connect", "failed to fetch", "no route to host", "name or service not known",
                "unknownhostexception", "connectexception", "sockettimeoutexception", "网络不可用",
            ).any { it in t } -> "网络不可用"
            "hash sum mismatch" in t || "checksum" in t && "mismatch" in t -> "下载内容校验失败，网络可能不稳定"
            exitCode == 137 || exitCode == -9 || exitCode == 9 || "killed" in t && exitCode != null && exitCode > 128 -> "被系统中止"
            "proot error" in t || "proot warning: can't" in t || "exec format error" in t || "permission denied" in t && "proot" in t -> "运行时无法启动"
            "could not get lock" in t || "unable to acquire the dpkg frontend lock" in t -> "另一个软件安装进程正在运行"
            "xz" in t && ("corrupt" in t || "format" in t) -> "安装包中的系统镜像已损坏"
            else -> null
        }
    }

    fun message(step: InstallStep?, log: List<String>, error: Throwable?, exitCode: Int?): String {
        val where = step?.let { "「${it.label}」未完成" } ?: "工作区创建未完成"
        if (error is InstallFailure) return "$where。${error.message}"
        val hint = when (cause(log, error, exitCode)) {
            "存储空间不足" -> "存储空间不足：请清理手机空间（至少保留 1 GB）后重试。"
            "网络不可用" -> "网络不可用：无法连接 Debian 软件源，请检查网络连接后重试。"
            "下载内容校验失败，网络可能不稳定" -> "下载内容校验失败，网络可能不稳定，请稍后重试。"
            "被系统中止" -> "进程被系统中止：可能是省电策略或后台限制，请保持 HaniyaX 在前台后重试。"
            "运行时无法启动" -> "运行时无法启动：此设备阻止了 proot 运行，请查看日志。"
            "另一个软件安装进程正在运行" -> "另一个软件安装进程正在运行，请稍后重试。"
            "安装包中的系统镜像已损坏" -> "安装包中的系统镜像已损坏，请重新安装 HaniyaX。"
            else -> when {
                exitCode != null -> "命令以退出码 $exitCode 结束，请查看日志。"
                error != null -> "出现错误：${error.message ?: error.javaClass.simpleName}"
                else -> "请查看日志了解原因。"
            }
        }
        return "$where。$hint"
    }
}

/**
 * Splits a character stream into lines on \n or \r\n. A bare \r (progress-bar redraw) overwrites the
 * current line, so only the final state of a redrawn line is emitted. Blank lines are dropped.
 */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val sb = StringBuilder()
    private var pendingCr = false

    fun feed(chars: CharArray, len: Int = chars.size) {
        for (i in 0 until len) {
            val ch = chars[i]
            when (ch) {
                '\n' -> { pendingCr = false; flushLine() }
                '\r' -> pendingCr = true
                else -> {
                    if (pendingCr) { sb.setLength(0); pendingCr = false }
                    if (sb.length < 8192) sb.append(ch)
                }
            }
        }
    }

    fun feed(text: String) = feed(text.toCharArray())

    fun finish() { pendingCr = false; if (sb.isNotEmpty()) flushLine() }

    private fun flushLine() {
        val line = sb.toString()
        sb.setLength(0)
        if (line.isNotBlank()) onLine(line)
    }
}

/** Host ProcessBuilder for a [LaunchSpec]: executable + argv[1..], environment cleared then set. */
fun LaunchSpec.toProcessBuilder(): ProcessBuilder {
    val cmd = ArrayList<String>(args.size)
    cmd += executable
    for (i in 1 until args.size) cmd += args[i]
    val pb = ProcessBuilder(cmd).directory(File(hostCwd))
    val e = pb.environment()
    e.clear()
    for (kv in env) {
        val i = kv.indexOf('=')
        if (i > 0) e[kv.substring(0, i)] = kv.substring(i + 1)
    }
    return pb
}

/** A failed step command; [code] is its exit status. */
class StepExit(val code: Int) : Exception("命令以退出码 $code 结束")

/** An install failure whose [message] is already a user-facing Chinese sentence. */
class InstallFailure(override val message: String) : Exception(message)

/** For the setup screen: which steps are done / running / failed. */
data class InstallProgressView(val done: Set<InstallStep>, val current: InstallStep?, val failed: InstallStep?)

// ---------------------------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------------------------

class WorkspaceImpl(private val context: Context) : WorkspaceApi {
    private val recordFile get() = File(Paths.state, "workspace.json")
    val installLogFile: File get() = File(Paths.state, "install.log")

    private val _state = MutableStateFlow<WorkspaceState>(WorkspaceState.NotInstalled)
    override val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(InstallProgressView(emptySet(), null, null))
    /** Step checklist for [WorkspaceSetupScreen]. */
    val progress: StateFlow<InstallProgressView> = _progress.asStateFlow()

    private val installMutex = Mutex()
    @Volatile private var installing = false
    private val runtimeLock = Any()
    @Volatile private var probedBinds: List<Bind>? = null

    // Install log ring + throttled state publishing.
    private val logRing = ArrayDeque<String>()
    private var logWriter: BufferedWriter? = null
    @Volatile private var curLabel = ""
    @Volatile private var curProgress: Float? = null
    @Volatile private var lastPublish = 0L
    @Volatile private var publishDirty = false

    private val layout: ProotLayout
        get() = ProotLayout(Paths.runtime.path, Paths.rootfs.path, Paths.files.path, Paths.tmp.path)

    init {
        val record = loadRecord()
        _progress.value = InstallProgressView(InstallStep.entries.filter(record::isDone).toSet(), null, null)
        if (WorkspaceSteps.allDone(record) && rootfsOk()) {
            _state.value = WorkspaceState.Ready
            App.scope.launch(Dispatchers.IO) { maintain() }
        }
    }

    // ---- public API ----

    override suspend fun install() {
        // A second call while one is running (double tap, another screen) waits for it instead of re-running.
        if (installMutex.isLocked) { installMutex.withLock { }; return }
        withContext(Dispatchers.IO) { installLocked() }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.installLocked() {
        installMutex.withLock {
            if (_state.value is WorkspaceState.Ready) return@withLock
            var step: InstallStep? = null
            synchronized(logRing) { logRing.clear() }
            openLog()
            installing = true
            // Publishing is throttled; this flushes log lines that arrived just before a quiet period.
            val ticker = App.scope.launch {
                while (true) {
                    delay(300)
                    if (publishDirty) publish(true)
                }
            }
            try {
                var record = loadRecord()
                val pending = WorkspaceSteps.pending(record, ::artifactOk)
                record = record.unmark(pending)
                saveRecord(record)
                _progress.value = InstallProgressView(InstallStep.entries.filter(record::isDone).toSet(), null, null)
                log("HaniyaX 工作区：待执行 ${pending.joinToString(" → ") { it.label }}")
                // An APK update may ship new runtime binaries even when the runtime step itself is already done
                // (e.g. retrying after a failed later step): refresh them by APK stamp before running guest steps.
                if (InstallStep.Runtime !in pending) installRuntime(force = false)
                for (s in pending) {
                    ensureActive()
                    step = s
                    _progress.value = _progress.value.copy(current = s, failed = null)
                    setStep(s.label, null)
                    log("== ${s.label} ==")
                    when (s) {
                        InstallStep.Runtime -> { checkSpace(500L shl 20); installRuntime(force = true) }
                        InstallStep.Rootfs -> { checkSpace(320L shl 20); extractRootfs() }
                        InstallStep.Configure -> configureGuest()
                        InstallStep.Packages -> { checkSpace(250L shl 20); installPackages() }
                        InstallStep.Sdk -> SdkInstaller.install(context, ::log)
                    }
                    record = record.markDone(s)
                    if (s == InstallStep.Sdk) record = record.copy(installedAt = System.currentTimeMillis())
                    saveRecord(record)
                    _progress.value = _progress.value.copy(done = _progress.value.done + s, current = null)
                }
                log("工作区已就绪")
                installing = false
                _state.value = WorkspaceState.Ready
            } catch (c: CancellationException) {
                installing = false
                val tail = logTail()
                withContext(NonCancellable) {
                    _progress.value = _progress.value.copy(current = null, failed = step)
                    _state.value = WorkspaceState.Failed("${step?.let { "「${it.label}」" } ?: ""}安装被中断，请重试。", tail)
                }
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "install failed at $step", t)
                log("错误：${t.message ?: t.javaClass.name}")
                installing = false
                val tail = logTail()
                _progress.value = _progress.value.copy(current = null, failed = step)
                _state.value = WorkspaceState.Failed(FailureClassifier.message(step, tail, t, (t as? StepExit)?.code), tail)
            } finally {
                ticker.cancel()
                installing = false
                closeLog()
            }
        }
    }

    override fun launch(argv: List<String>, cwd: String, extraEnv: Map<String, String>): LaunchSpec {
        ensureRuntimeExists()
        Paths.tmp.mkdirs()
        val l = layout
        val binds = probedBinds ?: Proot.probeBinds(l).also { probedBinds = it }
        return Proot.launchSpec(
            layout = l,
            guestCwd = resolveGuestCwd(cwd),
            argv = argv,
            extraEnv = extraEnv,
            optionalBinds = binds,
            androidRoot = System.getenv("ANDROID_ROOT"),
            androidData = System.getenv("ANDROID_DATA"),
        )
    }

    override fun shellLaunch(cwd: String, command: String?, extraEnv: Map<String, String>): LaunchSpec =
        launch(Proot.shellArgv(command), cwd, extraEnv)

    override suspend fun exec(script: String, cwd: String, timeoutMs: Long, extraEnv: Map<String, String>): ExecResult =
        withContext(Dispatchers.IO) {
            val proc = try {
                startProcess(launch(listOf("/bin/bash", "-lc", script), cwd, extraEnv), mergeStderr = false)
            } catch (e: Exception) {
                return@withContext ExecResult(127, "", "无法启动工作区进程：${e.message}")
            }
            runCatching { proc.outputStream.close() }
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = drain(proc.inputStream, out)
            val tErr = drain(proc.errorStream, err)
            val deadline = System.currentTimeMillis() + timeoutMs
            var timedOut = false
            try {
                while (proc.isAlive) {
                    if (System.currentTimeMillis() > deadline) { timedOut = true; break }
                    delay(25)
                }
            } catch (c: CancellationException) {
                kill(proc)
                throw c
            }
            if (timedOut) kill(proc)
            val code = if (timedOut) 124 else proc.waitFor()
            tOut.join(2000); tErr.join(2000)
            ExecResult(code, synchronized(out) { out.toString() }, synchronized(err) { err.toString() })
        }

    override fun spawn(script: String, cwd: String, hostLogFile: File, extraEnv: Map<String, String>): Process {
        hostLogFile.parentFile?.mkdirs()
        val spec = launch(listOf("/bin/bash", "-lc", script), cwd, extraEnv)
        val pb = processBuilder(spec)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(hostLogFile))
        val p = pb.start()
        runCatching { p.outputStream.close() }
        return p
    }

    // ---- process helpers (also used by JobsImpl) ----

    private fun processBuilder(spec: LaunchSpec): ProcessBuilder = spec.toProcessBuilder()

    private fun startProcess(spec: LaunchSpec, mergeStderr: Boolean): Process =
        processBuilder(spec).redirectErrorStream(mergeStderr).start()

    private fun drain(input: InputStream, into: StringBuilder): Thread = thread(name = "hx-exec-drain", isDaemon = true) {
        try {
            InputStreamReader(input, Charsets.UTF_8).use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(into) { if (into.length < MAX_CAPTURE) into.append(buf, 0, minOf(n, MAX_CAPTURE - into.length)) }
                }
            }
        } catch (_: IOException) { }
    }

    private fun kill(p: Process) {
        p.destroy()
        try { if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly() } catch (_: InterruptedException) { p.destroyForcibly() }
    }

    // ---- maintenance ----

    /** On start when Ready: refresh runtime binaries and SDK after an APK update. */
    private fun maintain() {
        try {
            installRuntime(force = false)
        } catch (t: Throwable) {
            Log.w(TAG, "runtime refresh failed", t)
        }
        try {
            if (SdkInstaller.needsRefresh(context)) SdkInstaller.install(context) { Log.i(TAG, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "sdk refresh failed", t)
        }
    }

    private fun ensureRuntimeExists() {
        if (File(layout.prootPath).canExecute() && File(layout.loaderPath).exists()) return
        installRuntime(force = true)
    }

    private val runtimeStampFile get() = File(Paths.runtime, ".stamp")

    /**
     * Extracts proot + loader + libs. Files are swapped in with per-file atomic renames so processes that
     * are currently running (and re-exec the loader on every guest execve) never see a missing file.
     */
    private fun installRuntime(force: Boolean) = synchronized(runtimeLock) {
        val stamp = SdkInstaller.apkIdentity(context).stamp
        val l = layout
        val ok = File(l.prootPath).canExecute() && File(l.loaderPath).exists() &&
            runCatching { runtimeStampFile.readText().trim() }.getOrNull() == stamp
        if (ok && !force) return@synchronized
        val runtime = Paths.runtime
        val partial = File(Paths.files, "runtime.partial")
        RootfsExtractor.deleteTree(partial)
        RootfsExtractor.extractAsset(context, "runtime/proot-${runtimeArch(context)}.tar.xz", partial, onProgress = { p -> setProgress(p) }, onLog = ::log)
        runtime.mkdirs()
        moveTreeInto(partial, runtime)
        RootfsExtractor.deleteTree(partial)
        for (f in listOf(File(runtime, "proot"), File(runtime, "loader"))) {
            if (!f.isFile) throw InstallFailure("运行时文件缺失：${f.name}，请重新安装 HaniyaX。")
            Os.chmod(f.path, 0x1C0)
        }
        File(runtime, "lib").listFiles()?.forEach { runCatching { Os.chmod(it.path, 0x1C0) } }
        Proot.writeFakeProc(l)
        Paths.tmp.mkdirs()
        runtimeStampFile.writeText(stamp)
        probedBinds = null
        log("运行时已就绪")
    }

    private fun moveTreeInto(src: File, dest: File) {
        src.listFiles()?.forEach { f ->
            val target = File(dest, f.name)
            if (Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    RootfsExtractor.deleteTree(target); target.mkdirs()
                }
                moveTreeInto(f, target)
            } else {
                Files.move(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    // ---- install steps ----

    private fun extractRootfs() {
        val partial = File(Paths.files, "rootfs.partial")
        RootfsExtractor.deleteTree(partial)
        val stats = RootfsExtractor.extractAsset(
            context, "runtime/debian-${runtimeArch(context)}.tar.xz", partial,
            onProgress = { p -> setProgress(p) },
            onLog = ::log,
        )
        log("已解压 ${stats.entries} 个条目（硬链接转为副本 ${stats.hardLinksCopied} 个，跳过设备文件等 ${stats.skipped} 个）")
        val bash = File(partial, "usr/bin/bash")
        if (!bash.isFile) throw InstallFailure("解压出的系统不完整（缺少 /usr/bin/bash），请重新安装 HaniyaX。")
        File(partial, "tmp").mkdirs()
        runCatching { Os.chmod(File(partial, "tmp").path, 0x3FF) }
        RootfsExtractor.deleteTree(Paths.rootfs)
        if (!partial.renameTo(Paths.rootfs)) throw InstallFailure("无法完成系统文件的移动，请重试。")
        setProgress(1f)
    }

    private fun configureGuest() {
        fun write(guest: String, text: String, mode: Int = 0x1A4) {
            val f = Paths.host(guest)
            f.parentFile?.mkdirs()
            Files.deleteIfExists(f.toPath()) // never write through a symlink
            f.writeText(text)
            runCatching { Os.chmod(f.path, mode) }
        }
        write("/etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        write(
            "/etc/hosts",
            "127.0.0.1   localhost\n::1         localhost ip6-localhost ip6-loopback\n",
        )
        write(
            "/etc/apt/apt.conf.d/99haniyax",
            "// HaniyaX: proot runs everything as a fake root, apt's _apt sandbox user can't work here.\n" +
                "APT::Sandbox::User \"root\";\n" +
                "Acquire::Retries \"3\";\n" +
                "Dpkg::Use-Pty \"0\";\n",
        )
        for (d in listOf(
            Paths.GUEST_PROJECTS, "${Paths.GUEST_HX}/plugins", "${Paths.GUEST_HX}/services",
            "${Paths.GUEST_HX}/logs", "${Paths.GUEST_HX}/backups", "/opt", "/root/.local/bin",
        )) Paths.host(d).mkdirs()
        Paths.host("/tmp").mkdirs()
        runCatching { Os.chmod(Paths.host("/tmp").path, 0x3FF) }
        write("/etc/profile.d/haniyax.sh", PROFILE_SNIPPET)
        val bashrc = Paths.host("/root/.bashrc")
        val existing = if (bashrc.isFile) bashrc.readText() else ""
        val cleaned = existing.replace(Regex("(?s)\\n?" + Regex.escape(BASHRC_BEGIN) + ".*?" + Regex.escape(BASHRC_END) + "\\n?"), "\n").trimEnd()
        val newText = (if (cleaned.isEmpty()) "" else cleaned + "\n\n") + BASHRC_SNIPPET
        Files.deleteIfExists(bashrc.toPath())
        bashrc.writeText(newText)
        log("已写入 DNS、hosts、apt 配置与 Shell 环境")
    }

    private fun ensureDebianSources() {
        val f = Paths.host("/etc/apt/sources.list.d/debian.sources")
        val text = if (f.isFile) f.readText() else ""
        val uris = text.lines().map { it.trim() }.filter { it.startsWith("URIs:") }.map { it.removePrefix("URIs:").trim() }
        val ok = uris.isNotEmpty() && uris.all { u -> u.split(Regex("\\s+")).all { it.matches(Regex("^https?://deb\\.debian\\.org/.*")) } }
        if (!ok) {
            log("软件源配置不符合预期，已改为 deb.debian.org")
            f.parentFile?.mkdirs()
            Files.deleteIfExists(f.toPath())
            f.writeText(DEFAULT_SOURCES)
        }
    }

    /** Throws [StepExit]/[InstallFailure] on failure. */
    private suspend fun installPackages() {
        ensureDebianSources()
        val probe = exec("echo hx-ok", timeoutMs = 60_000)
        if (!probe.stdout.contains("hx-ok")) {
            (probe.stdout + "\n" + probe.stderr).lines().filter { it.isNotBlank() }.takeLast(30).forEach(::log)
            throw InstallFailure("运行时无法启动 Debian（退出码 ${probe.code}），请查看日志。")
        }
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            dpkg --configure -a >/dev/null 2>&1 || true
            echo "::hx-phase:: update"
            apt-get --error-on=any -o APT::Status-Fd=3 update 3>&1
            echo "::hx-phase:: install"
            apt-get -o APT::Status-Fd=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
              install -y --no-install-recommends $BASE_PACKAGES 3>&1
            echo "基础软件安装完成"
        """.trimIndent()
        val apt = AptProgress()
        val code = runStreaming(script, timeoutMs = 45 * 60_000L) { line ->
            val p = apt.feed(line)
            if (p != null) setProgress(p)
            if (apt.isProtocol(line)) {
                apt.describe(line)?.let { if (line.startsWith("pmstatus:")) log(it) }
            } else log(line)
        }
        if (code != 0) throw StepExit(code)
        setProgress(1f)
    }

    /** Runs a script, streaming merged output lines to [onLine]. Returns exit code (124 on timeout). */
    private suspend fun runStreaming(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
        val proc = startProcess(launch(listOf("/bin/bash", "-lc", script), Paths.GUEST_HOME, emptyMap()), mergeStderr = true)
        runCatching { proc.outputStream.close() }
        val reader = thread(name = "hx-install-out", isDaemon = true) {
            try {
                val splitter = LineSplitter(onLine)
                InputStreamReader(proc.inputStream, Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        splitter.feed(buf, n)
                    }
                }
                splitter.finish()
            } catch (_: IOException) { }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (proc.isAlive) {
                if (System.currentTimeMillis() > deadline) { kill(proc); reader.join(2000); return 124 }
                delay(100)
            }
        } catch (c: CancellationException) {
            kill(proc); throw c
        }
        reader.join(3000)
        return proc.waitFor()
    }

    private fun checkSpace(required: Long) {
        val free = runCatching { StatFs(Paths.files.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < required) {
            throw InstallFailure("存储空间不足：需要约 ${required shr 20} MB，当前可用 ${free shr 20} MB。请清理空间后重试。")
        }
    }

    // ---- state / log helpers ----

    private fun artifactOk(step: InstallStep): Boolean = when (step) {
        InstallStep.Runtime -> File(layout.prootPath).isFile && File(layout.loaderPath).isFile
        InstallStep.Rootfs -> rootfsOk()
        InstallStep.Configure -> Paths.host("/etc/apt/apt.conf.d/99haniyax").isFile
        InstallStep.Packages -> Paths.host("/usr/bin/curl").isFile && Paths.host("/usr/bin/git").isFile
        InstallStep.Sdk -> File(SdkInstaller.sdkDir, "VERSION").isFile
    }

    private fun rootfsOk() = Paths.host("/usr/bin/bash").isFile

    private fun resolveGuestCwd(cwd: String): String {
        val c = cwd.ifBlank { Paths.GUEST_HOME }
        if (!c.startsWith("/")) return Paths.GUEST_HOME
        if (c.startsWith("/storage") || c.startsWith("/sdcard") || c.startsWith("/proc") || c.startsWith("/dev")) return c
        return if (Paths.host(c).isDirectory) c else Paths.GUEST_HOME
    }

    private fun loadRecord(): WorkspaceRecord =
        WorkspaceSteps.decode(runCatching { recordFile.takeIf { it.isFile }?.readText() }.getOrNull())

    private fun saveRecord(r: WorkspaceRecord) {
        val tmp = File(recordFile.path + ".tmp")
        tmp.writeText(WorkspaceSteps.encode(r))
        if (!tmp.renameTo(recordFile)) { recordFile.writeText(WorkspaceSteps.encode(r)); tmp.delete() }
    }

    private fun openLog() {
        closeLog()
        logWriter = runCatching { installLogFile.bufferedWriter() }.getOrNull()
    }

    private fun closeLog() {
        runCatching { logWriter?.close() }
        logWriter = null
    }

    private fun log(line: String) {
        synchronized(logRing) {
            logRing.addLast(line)
            while (logRing.size > LOG_KEEP) logRing.removeFirst()
            runCatching { logWriter?.apply { write(line); newLine(); flush() } }
        }
        publish(false)
    }

    private fun logTail(): List<String> = synchronized(logRing) { logRing.toList() }

    private fun setStep(label: String, progress: Float?) {
        curLabel = label; curProgress = progress
        publish(true)
    }

    private fun setProgress(p: Float?) {
        curProgress = p
        publish(false)
    }

    private fun publish(force: Boolean) {
        if (!installing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 120) { publishDirty = true; return }
        publishDirty = false
        lastPublish = now
        val cur = _state.value
        if (cur is WorkspaceState.Ready) return
        _state.value = WorkspaceState.Installing(curLabel.ifEmpty { InstallStep.Runtime.label }, curProgress, logTail())
    }

    companion object {
        private const val TAG = "HaniyaX.Workspace"
        private const val LOG_KEEP = 400
        private const val MAX_CAPTURE = 8 * 1024 * 1024
        /**
         * Runtime architecture: release APKs ship aarch64 only; debug builds also carry x86_64 assets so the
         * full workspace can run natively on x86_64 emulators (proot cannot run under ARM translation).
         */
        fun runtimeArch(context: android.content.Context): String {
            val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            val hasX86 = runCatching { context.assets.list("runtime")?.contains("proot-x86_64.tar.xz") == true }.getOrDefault(false)
            return if (primary == "x86_64" && hasX86) "x86_64" else "aarch64"
        }

        const val BASE_PACKAGES =
            "ca-certificates curl wget git unzip xz-utils procps less nano openssh-client ripgrep jq file psmisc"

        const val DEFAULT_SOURCES = """Types: deb
URIs: http://deb.debian.org/debian
Suites: trixie trixie-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

Types: deb
URIs: http://deb.debian.org/debian-security
Suites: trixie-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp
"""

        /** Debian's /etc/profile resets PATH for root; restore the workspace directories after it. */
        val PROFILE_SNIPPET = """# HaniyaX: keep the workspace PATH (Debian's /etc/profile resets it for root).
hx_path_prepend() { case ":${'$'}PATH:" in *":${'$'}1:"*) ;; *) PATH="${'$'}1:${'$'}PATH" ;; esac; }
for hx_d in /opt/node/bin /root/.opencode/bin /root/.local/bin /opt/haniyax/bin; do hx_path_prepend "${'$'}hx_d"; done
unset hx_d
unset -f hx_path_prepend
export PATH
"""

        const val BASHRC_BEGIN = "# >>> haniyax >>>"
        const val BASHRC_END = "# <<< haniyax <<<"
        val BASHRC_SNIPPET = """# >>> haniyax >>>
# Managed by HaniyaX; edits inside this block are replaced on reinstall.
case ":${'$'}PATH:" in
  *":/opt/haniyax/bin:"*) ;;
  *) export PATH="/opt/haniyax/bin:/root/.local/bin:/root/.opencode/bin:/opt/node/bin:${'$'}PATH" ;;
esac
if [[ ${'$'}- == *i* ]]; then
  PS1='\[\e[34m\]\w\[\e[0m\] › '
  PROMPT_COMMAND='printf "\033]0;%s\007" "${'$'}{PWD/#${'$'}HOME/~}"'
  alias ls='ls --color=auto'
  alias ll='ls -lh --color=auto'
fi
# <<< haniyax <<<
"""
    }
}
