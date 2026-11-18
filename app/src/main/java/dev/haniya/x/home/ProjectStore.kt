package dev.haniya.x.home

import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.projects.Project
import dev.haniya.x.projects.ProjectsApi
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.settings.HostFiles
import dev.haniya.x.settings.JsonStateFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** On-disk shape of Paths.state/projects.json. [hidden] = paths removed from the list (files are kept). */
@Serializable
data class ProjectsFile(val projects: List<Project> = emptyList(), val hidden: List<String> = emptyList())

/** Persisted projects ∪ directories under guest /root/projects. */
class ProjectStore : ProjectsApi {
    private val file = JsonStateFile(File(Paths.state, "projects.json"), ProjectsFile.serializer()) { ProjectsFile() }
    private val persisted = MutableStateFlow(file.load())

    private data class Scan(val dirs: List<ScannedDir> = emptyList(), val missing: Set<String> = emptySet())
    private val scan = MutableStateFlow(Scan())
    private val scanMutex = Mutex()

    private val _projects = MutableStateFlow(compute(persisted.value, Scan()))
    override val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        file.autosave(persisted, persisted.value)
        App.scope.launch { combine(persisted, scan) { p, s -> compute(p, s) }.collect { _projects.value = it } }
        App.scope.launch { Runtime.workspace.state.collect { if (it is WorkspaceState.Ready) refresh() } }
    }

    private fun compute(p: ProjectsFile, s: Scan): List<Project> =
        ProjectLogic.merge(p.projects, s.dirs, p.hidden.toSet()) { path -> path !in s.missing }

    override fun refresh() {
        App.scope.launch(Dispatchers.IO) { rescan() }
    }

    private suspend fun rescan() {
        scanMutex.withLock { scanLocked() }
    }

    private fun scanLocked() {
        try {
            val base = Paths.host(Paths.GUEST_PROJECTS)
            val dirs = base.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map {
                ScannedDir("${Paths.GUEST_PROJECTS}/${it.name}", it.name, it.lastModified())
            } ?: emptyList()
            val missing = persisted.value.projects.map { it.path }.filterTo(HashSet()) { !Paths.host(it).isDirectory }
            scan.value = Scan(dirs, missing)
        } catch (e: Exception) {
            Log.w(TAG, "扫描项目目录失败", e)
        }
    }

    override fun touch(path: String, agentId: String?) {
        val p = ProjectLogic.normalizePath(path)
        val now = System.currentTimeMillis()
        persisted.update { f ->
            val old = f.projects.firstOrNull { it.path == p }
            val next = (old ?: Project(path = p, name = Fmt.folderName(p))).copy(lastOpenedAt = now, lastAgent = agentId ?: old?.lastAgent)
            f.copy(projects = f.projects.filterNot { it.path == p } + next, hidden = f.hidden - p)
        }
        if (p in scan.value.missing) refresh()
    }

    override fun add(path: String): Project {
        val p = ProjectLogic.normalizePath(path)
        var result: Project? = null
        persisted.update { f ->
            val old = f.projects.firstOrNull { it.path == p }
            val next = old ?: Project(path = p, name = Fmt.folderName(p), lastOpenedAt = System.currentTimeMillis())
            result = next
            f.copy(projects = if (old != null) f.projects else f.projects + next, hidden = f.hidden - p)
        }
        refresh()
        return result ?: Project(path = p, name = Fmt.folderName(p))
    }

    override fun remove(path: String) {
        val p = ProjectLogic.normalizePath(path)
        persisted.update { f -> f.copy(projects = f.projects.filterNot { it.path == p }, hidden = (f.hidden - p) + p) }
    }

    override fun setPinned(path: String, pinned: Boolean) {
        val p = ProjectLogic.normalizePath(path)
        persisted.update { f ->
            val old = f.projects.firstOrNull { it.path == p }
            val next = (old ?: Project(path = p, name = Fmt.folderName(p))).copy(pinned = pinned)
            f.copy(projects = f.projects.filterNot { it.path == p } + next, hidden = f.hidden - p)
        }
    }

    override suspend fun create(name: String, gitUrl: String?): Result<String> = withContext(Dispatchers.IO) {
        val url = gitUrl?.trim()?.takeIf { it.isNotEmpty() }
        val finalName = name.trim().ifEmpty { url?.let { ProjectLogic.repoNameFromUrl(it) } ?: "" }
        ProjectLogic.validateName(finalName)?.let { return@withContext fail(it) }
        if (url != null) ProjectLogic.validateGitUrl(url)?.let { return@withContext fail(it) }
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) return@withContext fail("工作区还没有就绪")

        val path = "${Paths.GUEST_PROJECTS}/$finalName"
        val host = Paths.host(path)
        if (host.exists()) return@withContext fail("已有同名项目：$finalName")
        val base = Paths.host(Paths.GUEST_PROJECTS)
        if (!base.isDirectory && !base.mkdirs()) return@withContext fail("无法创建 ${Paths.GUEST_PROJECTS}")

        if (url == null) {
            if (!host.mkdirs()) return@withContext fail("无法创建目录 $path")
        } else {
            val q = ProjectLogic::shellQuote
            val script = "command -v git >/dev/null 2>&1 || { echo 'git: command not found' >&2; exit 127; }\n" +
                "git clone -- ${q(url)} ${q(finalName)}"
            val started = System.nanoTime()
            val r = Runtime.workspace.exec(
                script, cwd = Paths.GUEST_PROJECTS, timeoutMs = CLONE_TIMEOUT_MS,
                extraEnv = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_SSH_COMMAND" to "ssh -o BatchMode=yes"),
            )
            if (!r.ok) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val timedOut = elapsedMs >= CLONE_TIMEOUT_MS - 2_000
                // A killed clone can leave a partial checkout behind; it did not exist before, so remove it.
                if (host.exists()) HostFiles.deleteTree(host)
                return@withContext fail(ProjectLogic.gitError(r.stderr + "\n" + r.stdout, r.code, timedOut))
            }
            if (!host.isDirectory) return@withContext fail("克隆结束了，但没有找到目录 $path")
        }
        val now = System.currentTimeMillis()
        persisted.update { f ->
            f.copy(projects = f.projects.filterNot { it.path == path } + Project(path = path, name = finalName, lastOpenedAt = now), hidden = f.hidden - path)
        }
        rescan()
        Result.success(path)
    }

    private fun fail(message: String): Result<String> = Result.failure(IllegalStateException(message))

    companion object {
        private const val TAG = "HxProjects"
        const val CLONE_TIMEOUT_MS = 10L * 60 * 1000
    }
}
