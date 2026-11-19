package dev.haniya.x.providers

import android.system.Os
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** What a tool's live config currently says (read from the real files, not from our store). */
data class EffectiveConfig(
    val tool: Tool,
    val source: String,
    val baseUrl: String?,
    val model: String?,
    /** Human label: 官方账号登录 / API Key ••••1234 / 未配置 … */
    val auth: String,
    val overrides: List<String>,
    val matchedProviderId: String?,
)

@Serializable
data class SnapshotMeta(
    val tool: String,
    val createdAt: Long,
    /** Provider that was current before the write (null = unknown / not managed). */
    val previousProvider: String? = null,
    /** guest path → whether it existed. */
    val files: Map<String, Boolean> = emptyMap(),
)

data class Snapshot(val dir: File, val meta: SnapshotMeta)

/**
 * Guest-side store (~/.haniyax/providers.json, mode 0600) and the live-config writer. Every write is preceded by
 * a snapshot under ~/.haniyax/backups/providers/<tool>/<time>/ (last 20 kept), so the previous working config can
 * be restored. Files are replaced atomically (temp + fsync + rename); secrets are written 0600.
 */
object ProviderStore {
    private val lock = Mutex()
    private val storeGuest = "${Paths.GUEST_HX}/providers.json"
    private val _file = MutableStateFlow(ProviderFile())
    val file: StateFlow<ProviderFile> = _file.asStateFlow()

    fun livePaths(t: Tool): List<String> = when (t) {
        Tool.Claude -> listOf("/root/.claude/settings.json")
        Tool.Codex -> listOf("/root/.codex/config.toml", "/root/.codex/auth.json")
        Tool.OpenCode -> listOf("/root/.config/opencode/opencode.json")
    }

    private fun host(guest: String) = Paths.host(guest)
    private fun read(guest: String): String? = host(guest).takeIf { it.isFile }?.readText()

    suspend fun load(): ProviderFile = withContext(Dispatchers.IO) {
        val f = host(storeGuest)
        val v = if (f.isFile) runCatching { AppJson.decodeFromString(ProviderFile.serializer(), f.readText()) }.getOrElse { ProviderFile() } else ProviderFile()
        _file.value = v
        v
    }

    private fun saveStore(v: ProviderFile) {
        writeAtomic(host(storeGuest), AppJson.encodeToString(ProviderFile.serializer(), v), secret = true)
        _file.value = v
    }

    suspend fun upsert(p: Provider): Provider = lock.withLock {
        withContext(Dispatchers.IO) {
            val cur = load()
            val withId = if (p.id.isBlank()) p.copy(id = UUID.randomUUID().toString().take(8), createdAt = System.currentTimeMillis()) else p
            val list = cur.providers.filter { it.id != withId.id } + withId
            saveStore(cur.copy(providers = list))
            withId
        }
    }

    suspend fun delete(id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val cur = load()
            saveStore(cur.copy(providers = cur.providers.filter { it.id != id }, current = cur.current.filterValues { it != id }))
        }
    }

    /** Writes [p] into the tool's live config. Throws with a Chinese message when a live file cannot be parsed. */
    suspend fun switchTo(t: Tool, p: Provider) = lock.withLock {
        withContext(Dispatchers.IO) {
            val cur = load()
            // Compute every new file first; nothing is written if any input cannot be parsed.
            val writes = LinkedHashMap<String, Pair<String, Boolean>>() // guest path → (content, secret)
            when (t) {
                Tool.Claude -> writes["/root/.claude/settings.json"] = ClaudePatch.apply(read("/root/.claude/settings.json"), p) to true
                Tool.Codex -> {
                    val stashFile = host("${Paths.GUEST_HX}/backups/providers/codex-login-auth.json")
                    val stashed = stashFile.takeIf { it.isFile }?.readText()
                    val (auth, stash) = CodexPatch.applyAuth(read("/root/.codex/auth.json"), p, stashed)
                    if (stash != null) writeAtomic(stashFile, stash, secret = true)
                    writes["/root/.codex/config.toml"] = CodexPatch.applyConfig(read("/root/.codex/config.toml"), p) to false
                    writes["/root/.codex/auth.json"] = auth to true
                }
                Tool.OpenCode -> writes["/root/.config/opencode/opencode.json"] = OpenCodePatch.apply(read("/root/.config/opencode/opencode.json"), p) to true
            }
            snapshot(t, cur.current[t.id])
            for ((guest, v) in writes) writeAtomic(host(guest), v.first, v.second)
            saveStore(cur.copy(current = cur.current + (t.id to p.id)))
        }
    }

    // ---- snapshots ----

    private fun snapshotRoot(t: Tool) = host("${Paths.GUEST_HX}/backups/providers/${t.id}")

    private fun snapshot(t: Tool, previous: String?) {
        val now = System.currentTimeMillis()
        val dir = File(snapshotRoot(t), SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now)))
        dir.mkdirs()
        runCatching { Os.chmod(dir.path, "700".toInt(8)) }
        val existed = LinkedHashMap<String, Boolean>()
        for (g in livePaths(t)) {
            val src = host(g)
            existed[g] = src.isFile
            if (src.isFile) writeAtomic(File(dir, g.trimStart('/').replace('/', '_')), src.readText(), secret = true)
        }
        writeAtomic(File(dir, "meta.json"), AppJson.encodeToString(SnapshotMeta.serializer(), SnapshotMeta(t.id, now, previous, existed)), secret = true)
        // Keep the 20 newest.
        snapshotRoot(t).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(20)?.forEach { it.deleteRecursively() }
    }

    suspend fun snapshots(t: Tool): List<Snapshot> = withContext(Dispatchers.IO) {
        snapshotRoot(t).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.mapNotNull { d ->
            runCatching { Snapshot(d, AppJson.decodeFromString(SnapshotMeta.serializer(), File(d, "meta.json").readText())) }.getOrNull()
        }.orEmpty()
    }

    /** Restores the newest snapshot (the config before the last switch) and consumes it. */
    suspend fun restoreLatest(t: Tool): Snapshot? = lock.withLock {
        withContext(Dispatchers.IO) {
            val snap = snapshots(t).firstOrNull() ?: return@withContext null
            for ((g, existed) in snap.meta.files) {
                val target = host(g)
                if (existed) {
                    val saved = File(snap.dir, g.trimStart('/').replace('/', '_'))
                    if (saved.isFile) writeAtomic(target, saved.readText(), secret = true)
                } else target.delete()
            }
            val cur = load()
            val prev = snap.meta.previousProvider
            saveStore(cur.copy(current = if (prev != null) cur.current + (t.id to prev) else cur.current - t.id))
            snap.dir.deleteRecursively()
            snap
        }
    }

    // ---- effective config ----

    suspend fun effective(t: Tool): EffectiveConfig = withContext(Dispatchers.IO) {
        val store = load()
        val overrides = listOf("/root/.bashrc", "/root/.profile", "/root/.zshrc", "/etc/environment").flatMap { g ->
            read(g)?.let { Overrides.find(g, it) }.orEmpty()
        } + (host("/etc/profile.d").listFiles()?.flatMap { f -> if (f.isFile) Overrides.find("/etc/profile.d/${f.name}", f.readText()) else emptyList() }.orEmpty())
        val relevant = overrides.filter { o ->
            when (t) { Tool.Claude -> o.contains("ANTHROPIC_") || o.contains("CLAUDE_CODE_"); Tool.Codex -> o.contains("OPENAI_"); Tool.OpenCode -> true }
        }
        val current = store.current[t.id]
        when (t) {
            Tool.Claude -> {
                val e = ClaudePatch.read(read("/root/.claude/settings.json"))
                val loggedIn = host("/root/.claude/.credentials.json").isFile || (read("/root/.claude.json")?.contains("\"oauthAccount\"") == true)
                EffectiveConfig(
                    t, "~/.claude/settings.json", e.baseUrl, e.model,
                    auth = when { e.key != null -> "${e.keyKind} ${Masks.key(e.key)}"; loggedIn -> "官方账号 · 已登录"; else -> "官方账号 · 未登录" },
                    overrides = relevant, matchedProviderId = current,
                )
            }
            Tool.Codex -> {
                val e = CodexPatch.read(read("/root/.codex/config.toml"), read("/root/.codex/auth.json"))
                EffectiveConfig(
                    t, "~/.codex/config.toml", e.baseUrl ?: if (e.provider == null || e.provider == "openai") "OpenAI 官方" else null, e.model,
                    auth = when (e.authKind) { "OPENAI_API_KEY" -> "API Key ${Masks.key(e.key)}"; "chatgpt" -> "ChatGPT 账号登录"; else -> "未登录" },
                    overrides = relevant, matchedProviderId = current,
                )
            }
            Tool.OpenCode -> {
                val e = OpenCodePatch.read(read("/root/.config/opencode/opencode.json"))
                EffectiveConfig(
                    t, "~/.config/opencode/opencode.json", e.baseUrl, e.model,
                    auth = if (e.key != null) "API Key ${Masks.key(e.key)}" else "OpenCode 自带登录（opencode auth login）",
                    overrides = relevant, matchedProviderId = current,
                )
            }
        }
    }

    // ---- io ----

    /** Atomic replace; follows a symlinked target; 0600 when [secret]. */
    fun writeAtomic(target0: File, text: String, secret: Boolean) {
        val target = runCatching { if (java.nio.file.Files.isSymbolicLink(target0.toPath())) target0.canonicalFile else target0 }.getOrDefault(target0)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.hx-tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (secret) runCatching { Os.chmod(tmp.path, "600".toInt(8)) }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("无法写入 ${target.path}")
        }
    }
}
