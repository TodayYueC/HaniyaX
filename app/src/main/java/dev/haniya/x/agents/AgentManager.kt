package dev.haniya.x.agents

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.JobState
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Persisted in Paths.state/agents.json so the screen can render the last known state instantly. */
@Serializable
data class AgentCache(
    val statuses: Map<String, CachedStatus> = emptyMap(),
    val latest: Map<String, CachedLatest> = emptyMap(),
    /** Per-agent opt-in: start with the OS sandbox turned off (only for agents that have such a switch). */
    val fullAccess: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class CachedStatus(val installed: Boolean, val version: String? = null, val path: String? = null, val source: String? = null)

@Serializable
data class CachedLatest(val version: String? = null, val checkedAt: Long = 0)

/**
 * Agent management: detection, on-demand install/update jobs, session discovery and launch.
 * Implements the AgentsApi contract; extra UI-facing state (last job, full-access opt-in) is public below.
 */
class AgentManager : AgentsApi {

    override val agents: List<AgentInfo> = Adapters.all.map { it.info }

    private val cacheFile = File(Paths.state, "agents.json")
    private val cacheLock = Any()
    private var cache: AgentCache = loadCache()

    private val _status = MutableStateFlow(initialStatus(cache))
    override val status: StateFlow<Map<String, AgentStatus>> = _status.asStateFlow()

    private val _lastJobs = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Most recent install/update job per agent (kept after it ends so failures can be shown with their log). */
    val lastJobs: StateFlow<Map<String, String>> = _lastJobs.asStateFlow()

    private val _fullAccess = MutableStateFlow(cache.fullAccess)
    val fullAccess: StateFlow<Map<String, Boolean>> = _fullAccess.asStateFlow()

    private val refreshJobs = HashMap<String, Job>()
    private val watchedJobs = HashSet<String>()
    private val jobKinds = HashMap<String, String>()
    private val latestAttempt = HashMap<String, Long>()
    private val hintsMutex = Mutex()

    init {
        // Detect whenever the workspace becomes ready (app start, after setup or repair).
        App.scope.launch {
            Runtime.workspace.state.map { it is WorkspaceState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) refresh()
            }
        }
        // Keep the SDK hint blocks in line with the setting.
        App.scope.launch {
            combine(
                App.settings.settings.map { it.agentSdkHints }.distinctUntilChanged(),
                Runtime.workspace.state.map { it is WorkspaceState.Ready }.distinctUntilChanged(),
            ) { on, ready -> on to ready }.collect { (on, ready) -> if (ready) syncHints(on) }
        }
    }

    // ------------------------------------------------------------------ AgentsApi

    override fun refresh(agentId: String?) {
        val targets = if (agentId == null) Adapters.all else listOfNotNull(Adapters.find(agentId))
        for (a in targets) refreshOne(a)
    }

    override fun install(agentId: String): String = startJob(Adapters.get(agentId), KIND_INSTALL, null)

    override fun update(agentId: String): String {
        val a = Adapters.get(agentId)
        // Install exactly the version the user confirmed; the script resolves "latest" itself when unknown.
        val target = (status.value[agentId] as? AgentStatus.Installed)?.latest
        return startJob(a, KIND_UPDATE, target)
    }

    override suspend fun sessions(agentId: String, projectPath: String): List<AgentSession> {
        val a = Adapters.find(agentId) ?: return emptyList()
        val cwd = Sessions.normalizeDir(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                when (a.id) {
                    "claude" -> Sessions.claudeSessions(Paths.host(Paths.GUEST_HOME), cwd)
                    "codex" -> Sessions.codexSessions(Paths.host(Paths.GUEST_HOME), cwd)
                    "opencode" -> opencodeSessions(a, cwd)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sessions($agentId) failed: ${e.message}")
                emptyList()
            }
        }
    }

    override fun launch(agentId: String, projectPath: String, resume: String?): String {
        val a = Adapters.get(agentId)
        val r = when {
            resume == null -> null
            resume.isBlank() -> ""
            else -> resume.trim().also { require(AgentAdapter.isValidSessionId(it)) { "会话编号无效：$it" } }
        }
        val cwd = Sessions.normalizeDir(projectPath)
        AgentThemes.ensureFor(a.id)
        val full = a.supportsFullAccess && fullAccess.value[a.id] == true
        val command = a.command(r, full)
        // After the process is gone, reopen this exact conversation, or the most recent one in this folder.
        val resumeCommand = a.command(if (r.isNullOrEmpty()) "" else r, full)
        val taskId = Runtime.tasks.start(
            TaskSpec(
                kind = TaskKind.Agent,
                title = "${a.info.name} · ${folderLabel(cwd)}",
                cwd = cwd,
                command = command,
                agentId = a.id,
                resumeCommand = resumeCommand,
            ),
        )
        App.projects.touch(cwd, a.id)
        onMain { Nav.go(Route.Terminal(taskId)) }
        return taskId
    }

    override fun runningTasks(agentId: String): List<String> =
        Runtime.tasks.tasks.value.filter { it.agentId == agentId && it.status == TaskStatus.Running }.map { it.id }

    // ------------------------------------------------------------------ UI helpers

    fun setFullAccess(agentId: String, on: Boolean) {
        _fullAccess.update { it + (agentId to on) }
        editCache { it.copy(fullAccess = _fullAccess.value) }
    }

    /** "install" or "update" for the most recent job of [agentId], so a failed job can be retried as the same kind. */
    fun lastJobKind(agentId: String): String? = synchronized(jobKinds) { jobKinds[agentId] }

    /** Hides the result of the last job (e.g. a failure notice the user has read). */
    fun dismissJob(agentId: String) {
        _lastJobs.update { it - agentId }
    }

    // ------------------------------------------------------------------ detection

    /** Starts detection for [a]. With [force], a check already in flight is cancelled so its (older) result is dropped. */
    private fun refreshOne(a: AgentAdapter, force: Boolean = false) {
        synchronized(refreshJobs) {
            val prev = refreshJobs[a.id]
            if (prev?.isActive == true && !force) return
            refreshJobs[a.id] = App.scope.launch {
                prev?.cancelAndJoin()
                detect(a)
            }
        }
    }

    private suspend fun detect(a: AgentAdapter) {
        Runtime.jobs.runningForKey(jobKey(a.id))?.let { snap ->
            setStatus(a.id, AgentStatus.Busy(snap.id, synchronized(jobKinds) { jobKinds[a.id] } ?: KIND_INSTALL))
            watch(a, snap.id)
            return
        }
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) return
        when (_status.value[a.id]) {
            null, AgentStatus.Unknown, is AgentStatus.Error -> setStatus(a.id, AgentStatus.Checking)
            else -> Unit // keep showing the cached state while re-checking
        }
        checkLatest(a)
        val r = Runtime.workspace.exec(a.detectScript(), timeoutMs = 90_000, extraEnv = a.env)
        val next: AgentStatus = when (val d = DetectParser.parse(r.stdout, a.versionRegex)) {
            Detection.Missing -> AgentStatus.NotInstalled
            is Detection.Found -> withLatest(
                a,
                AgentStatus.Installed(
                    version = d.version ?: "",
                    path = d.path,
                    source = if (markerFile(a.id).isFile && a.isManagedPath(d.realPath)) SOURCE_HANIYAX else SOURCE_USER,
                    latest = null,
                    latestVerified = false,
                ),
            )
            null -> AgentStatus.Error(detectError(r.code, r.stderr))
        }
        // An install that started while we were checking owns the status now.
        if (_status.value[a.id] is AgentStatus.Busy) return
        setStatus(a.id, next)
        when (next) {
            is AgentStatus.Installed -> editCache { c ->
                c.copy(statuses = c.statuses + (a.id to CachedStatus(true, next.version, next.path, next.source)))
            }
            AgentStatus.NotInstalled -> editCache { c -> c.copy(statuses = c.statuses + (a.id to CachedStatus(false))) }
            else -> Unit
        }
        syncHints(App.settings.settings.value.agentSdkHints)
    }

    private fun detectError(code: Int, stderr: String): String {
        val last = stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        return when {
            code == 124 -> "检查超时，工作区可能正忙，请稍后重试"
            last != null -> "无法检查安装状态：$last"
            else -> "无法检查安装状态（退出码 $code）"
        }
    }

    private fun markerFile(id: String): File = Paths.host("${Paths.GUEST_HX}/agents/$id.json")

    // ------------------------------------------------------------------ latest version (host HTTP, cached 6 h)

    private fun checkLatest(a: AgentAdapter) {
        val now = System.currentTimeMillis()
        val cached = synchronized(cacheLock) { cache.latest[a.id] }
        if (cached != null && now - cached.checkedAt < LATEST_TTL_MS) return
        synchronized(latestAttempt) {
            val last = latestAttempt[a.id] ?: 0L
            if (now - last < LATEST_RETRY_MS) return
            latestAttempt[a.id] = now
        }
        App.scope.launch(Dispatchers.IO) {
            val fetched: String? = try {
                fetchLatest(a.latest)
            } catch (e: Exception) {
                Log.i(TAG, "latest(${a.id}) failed: ${e.message}")
                null
            }
            val v = fetched ?: return@launch
            editCache { c -> c.copy(latest = c.latest + (a.id to CachedLatest(v, System.currentTimeMillis()))) }
            _status.update { m ->
                val s = m[a.id] as? AgentStatus.Installed ?: return@update m
                m + (a.id to withLatest(a, s))
            }
        }
    }

    private fun withLatest(a: AgentAdapter, s: AgentStatus.Installed): AgentStatus.Installed {
        val l = synchronized(cacheLock) { cache.latest[a.id]?.version }
        val newer = l != null && Semver.isNewer(l, s.version)
        return s.copy(latest = if (newer) l else null, latestVerified = newer && isVerified(a, l))
    }

    private fun isVerified(a: AgentAdapter, version: String?): Boolean {
        val v = Semver.parse(version) ?: return false
        return v.major <= a.verifiedMajor && !v.isPrerelease
    }

    private fun fetchLatest(src: LatestSource): String? = when (src) {
        is LatestSource.PlainText -> DetectParser.latestFromPlain(httpGet(src.url))
        is LatestSource.JsonField -> DetectParser.latestFromJson(httpGet(src.url), src.field)
        is LatestSource.GithubLatestRedirect -> {
            val c = open(src.url)
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                c.connect()
                DetectParser.latestFromLocation(c.getHeaderField("Location"), src.tagPrefix)
            } finally {
                c.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "HaniyaX")
    }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).take(64 * 1024) }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ jobs

    private fun startJob(a: AgentAdapter, kind: String, version: String?): String {
        val title = (if (kind == KIND_UPDATE) "更新 " else "安装 ") + a.info.name
        val jobId = Runtime.jobs.start(title, a.jobScript(kind, version), key = jobKey(a.id), extraEnv = a.env)
        synchronized(jobKinds) { jobKinds[a.id] = kind }
        _lastJobs.update { it + (a.id to jobId) }
        setStatus(a.id, AgentStatus.Busy(jobId, kind))
        watch(a, jobId)
        return jobId
    }

    private fun watch(a: AgentAdapter, jobId: String) {
        synchronized(watchedJobs) { if (!watchedJobs.add(jobId)) return }
        App.scope.launch {
            Runtime.jobs.get(jobId).first { it == null || it.state != JobState.Running }
            synchronized(watchedJobs) { watchedJobs.remove(jobId) }
            _status.update { m ->
                if ((m[a.id] as? AgentStatus.Busy)?.jobId == jobId) m + (a.id to (cachedStatus(a) ?: AgentStatus.Checking)) else m
            }
            // A finished install/update may change both the version and what "newer" means.
            refreshOne(a, force = true)
        }
    }

    private fun cachedStatus(a: AgentAdapter): AgentStatus? {
        val c = synchronized(cacheLock) { cache.statuses[a.id] } ?: return null
        return if (c.installed && c.path != null) {
            withLatest(a, AgentStatus.Installed(c.version ?: "", c.path, c.source ?: SOURCE_USER, null, false))
        } else if (!c.installed) AgentStatus.NotInstalled else null
    }

    // ------------------------------------------------------------------ sessions (OpenCode asks its own CLI)

    private suspend fun opencodeSessions(a: AgentAdapter, cwd: String): List<AgentSession> {
        if (_status.value[a.id] !is AgentStatus.Installed) return emptyList()
        if (!Paths.host(cwd).isDirectory) return emptyList()
        val env = a.env.entries.joinToString("") { (k, v) -> "export $k=${AgentAdapter.shQuote(v)}; " }
        val script = AgentAdapter.PATH_PREFIX + env +
            "timeout 45 opencode session list --standalone --format json --max-count ${Sessions.MAX_SESSIONS} </dev/null"
        val r = Runtime.workspace.exec(script, cwd = cwd, timeoutMs = 50_000, extraEnv = a.env)
        if (!r.ok) return emptyList()
        return Sessions.parseOpencodeList(r.stdout, cwd)
    }

    // ------------------------------------------------------------------ SDK hints

    private fun syncHints(enabled: Boolean) {
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) return
        val installed = _status.value.filterValues { it is AgentStatus.Installed }.keys
        App.scope.launch(Dispatchers.IO) {
            hintsMutex.withLock { SdkHints.sync(enabled, installed) }
        }
    }

    // ------------------------------------------------------------------ state + cache

    private fun setStatus(id: String, s: AgentStatus) {
        _status.update { it + (id to s) }
    }

    private fun initialStatus(c: AgentCache): Map<String, AgentStatus> = Adapters.all.associate { a ->
        val s = c.statuses[a.id]
        a.id to when {
            s == null -> AgentStatus.Unknown
            s.installed && s.path != null -> {
                val l = c.latest[a.id]?.version
                val newer = l != null && Semver.isNewer(l, s.version)
                AgentStatus.Installed(s.version ?: "", s.path, s.source ?: SOURCE_USER, if (newer) l else null, newer && isVerified(a, l))
            }
            !s.installed -> AgentStatus.NotInstalled
            else -> AgentStatus.Unknown
        }
    }

    private fun loadCache(): AgentCache = try {
        if (cacheFile.isFile) AppJson.decodeFromString(AgentCache.serializer(), cacheFile.readText()) else AgentCache()
    } catch (e: Exception) {
        Log.w(TAG, "agents.json unreadable, starting fresh: ${e.message}")
        AgentCache()
    }

    private fun editCache(transform: (AgentCache) -> AgentCache) {
        val text = synchronized(cacheLock) {
            cache = transform(cache)
            AppJson.encodeToString(AgentCache.serializer(), cache)
        }
        App.scope.launch(Dispatchers.IO) {
            synchronized(cacheFile) {
                try {
                    val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(cacheFile)) cacheFile.writeText(text)
                } catch (e: Exception) {
                    Log.w(TAG, "saving agents.json failed: ${e.message}")
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val TAG = "Agents"
        const val KIND_INSTALL = "install"
        const val KIND_UPDATE = "update"
        const val SOURCE_HANIYAX = "haniyax"
        const val SOURCE_USER = "user"
        private const val LATEST_TTL_MS = 6 * 60 * 60 * 1000L
        private const val LATEST_RETRY_MS = 10 * 60 * 1000L

        fun jobKey(agentId: String) = "agent:$agentId"

        /** "主目录" for /root, else the last path segment. */
        fun folderLabel(cwd: String): String =
            if (cwd == Paths.GUEST_HOME) "主目录" else cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }

        /** The manager behind App.agents, for UI that needs the extras (null in tests / before wiring). */
        val instance: AgentManager? get() = runCatching { App.agents as? AgentManager }.getOrNull()
    }
}
