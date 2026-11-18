package dev.haniya.x.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Reads each agent's own session history so the launch sheet can offer "continue" / "pick one".
 * Everything here is read-only and pure JVM (unit tested). The formats are internal to each tool and may
 * change between releases, so every parser is tolerant: anything unrecognised is skipped, never fatal.
 */
object Sessions {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val MAX_SESSIONS = 20
    private const val TITLE_MAX = 80
    /** Lines read from the head of a transcript when looking for a title. */
    private const val HEAD_LINES = 400
    /** Upper bound on bytes read from one transcript head (session_meta lines can be large). */
    private const val HEAD_BYTES = 2L * 1024 * 1024

    // ---------------------------------------------------------------- Claude Code

    /**
     * Claude Code stores transcripts in ~/.claude/projects/<encoded cwd>/<session-id>.jsonl where the encoded name is
     * the absolute cwd with every non-alphanumeric character replaced by '-' (code.claude.com/docs/en/sessions,
     * "Where transcripts are stored"). Names longer than 200 chars are truncated to 200 and get a hash suffix.
     */
    fun claudeProjectDirName(cwd: String): String = buildString(cwd.length) {
        for (ch in cwd) append(if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') ch else '-')
    }

    const val CLAUDE_NAME_LIMIT = 200

    /** "/root/x/" and "/root/x" are the same project; "/" stays "/". */
    fun normalizeCwd(cwd: String): String = cwd.trimEnd('/').ifEmpty { "/" }

    /** Candidate project directories for [cwd] under [projectsDir] (exact name, or truncated name + hash suffix). */
    fun claudeProjectDirs(projectsDir: File, cwd: String): List<File> {
        val name = claudeProjectDirName(cwd)
        if (name.length <= CLAUDE_NAME_LIMIT) return listOf(File(projectsDir, name)).filter { it.isDirectory }
        val prefix = name.take(CLAUDE_NAME_LIMIT)
        return projectsDir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }?.toList().orEmpty()
    }

    data class ClaudeHead(val title: String?, val cwd: String?, val hasUserMessage: Boolean)

    /** Extracts a display title and the recorded cwd from the first lines of a Claude transcript. */
    fun parseClaudeHead(lines: Sequence<String>): ClaudeHead {
        var customTitle: String? = null
        var summary: String? = null
        var firstPrompt: String? = null
        var cwd: String? = null
        var hasUser = false
        for (line in lines) {
            val o = parseObject(line) ?: continue
            if (cwd == null) cwd = o.str("cwd")
            when (o.str("type")) {
                "custom-title" -> o.str("customTitle")?.let { customTitle = it }
                "summary" -> if (summary == null) summary = o.str("summary")
                "user" -> {
                    if (o.bool("isMeta") == true || o.bool("isSidechain") == true) continue
                    val text = claudeUserText(o["message"]) ?: continue
                    if (isClaudeNoise(text)) continue
                    hasUser = true
                    if (firstPrompt == null) firstPrompt = text
                }
            }
        }
        val title = (customTitle ?: summary ?: firstPrompt)?.let { cleanTitle(it) }
        return ClaudeHead(title, cwd, hasUser)
    }

    private fun claudeUserText(message: JsonElement?): String? {
        val m = message as? JsonObject ?: return null
        if (m.str("role") != null && m.str("role") != "user") return null
        return when (val c = m["content"]) {
            is JsonPrimitive -> c.takeIf { it.isString }?.content
            is JsonArray -> c.firstNotNullOfOrNull { part ->
                val p = part as? JsonObject ?: return@firstNotNullOfOrNull null
                if (p.str("type") == "text") p.str("text") else null
            }
            else -> null
        }?.trim()?.ifEmpty { null }
    }

    /** Slash-command wrappers, local command output and caveats are not real prompts. */
    private fun isClaudeNoise(text: String): Boolean =
        text.startsWith("<command-") || text.startsWith("<local-command") || text.startsWith("Caveat:") ||
            text.startsWith("<system-reminder>") || text.startsWith("[Request interrupted")

    /** Sessions for [cwd]: newest first, only transcripts that hold at least one real prompt. */
    fun claudeSessions(home: File, cwd0: String, limit: Int = MAX_SESSIONS): List<AgentSession> {
        val cwd = normalizeCwd(cwd0)
        val projects = File(home, ".claude/projects")
        val files = claudeProjectDirs(projects, cwd)
            .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && f.length() > 0 }?.toList().orEmpty() }
            .sortedByDescending { it.lastModified() }
        val out = ArrayList<AgentSession>()
        for (f in files) {
            if (out.size >= limit) break
            val head = runCatching { parseClaudeHead(headLines(f)) }.getOrNull() ?: continue
            if (!head.hasUserMessage) continue
            // The directory name is lossy ("/a.b" and "/a-b" collide); trust the recorded cwd when present.
            if (head.cwd != null && normalizeDir(head.cwd) != normalizeDir(cwd)) continue
            val id = f.name.removeSuffix(".jsonl")
            if (!AgentAdapter.isValidSessionId(id)) continue
            out += AgentSession(id, head.title ?: "未命名会话", f.lastModified())
        }
        return out
    }

    // ---------------------------------------------------------------- Codex

    data class CodexMeta(val id: String, val cwd: String, val source: String?, val timestamp: String?)

    /**
     * Parses a rollout line `{"timestamp":…,"type":"session_meta","payload":{"id":…,"cwd":…,"source":…}}`
     * (codex-rs/protocol SessionMetaLine, rust-v0.169.0). Returns null for any other line.
     */
    fun parseCodexMeta(line: String): CodexMeta? {
        val o = parseObject(line) ?: return null
        if (o.str("type") != "session_meta") return null
        val p = o["payload"] as? JsonObject ?: return null
        val id = p.str("id") ?: return null
        val cwd = p.str("cwd") ?: return null
        val source = when (val s = p["source"]) {
            is JsonPrimitive -> s.takeIf { it.isString }?.content
            is JsonObject -> s.keys.firstOrNull() // e.g. {"subagent": …}
            else -> null
        }
        return CodexMeta(id, cwd, source, p.str("timestamp"))
    }

    /** Sessions the interactive `codex resume` picker is about (not `codex exec`, MCP or sub-agents). */
    fun isInteractiveCodexSource(source: String?): Boolean =
        source == null || source.equals("cli", true) || source.equals("vscode", true)

    /** First real user prompt in a rollout: event_msg/user_message, else a response_item user message. */
    fun codexFirstPrompt(lines: Sequence<String>): String? {
        var fallback: String? = null
        for (line in lines) {
            val o = parseObject(line) ?: continue
            val p = o["payload"] as? JsonObject ?: continue
            when (o.str("type")) {
                "event_msg" -> if (p.str("type") == "user_message") {
                    p.str("message")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { return it }
                }
                "response_item" -> if (fallback == null && p.str("type") == "message" && p.str("role") == "user") {
                    val parts = p["content"] as? JsonArray
                    fallback = parts?.firstNotNullOfOrNull { part ->
                        val q = part as? JsonObject ?: return@firstNotNullOfOrNull null
                        q.str("text")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
                    }
                }
            }
        }
        return fallback
    }

    /** ~/.codex/session_index.jsonl: append-only `{"id","thread_name","updated_at"}`; the last entry per id wins. */
    fun parseCodexSessionIndex(lines: Sequence<String>): Map<String, String> {
        val names = HashMap<String, String>()
        for (line in lines) {
            val o = parseObject(line) ?: continue
            val id = o.str("id") ?: continue
            val name = o.str("thread_name")?.trim()
            if (name.isNullOrEmpty()) names.remove(id) else names[id] = name
        }
        return names
    }

    /**
     * Walks ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl newest first and keeps the ones whose session_meta cwd is
     * [cwd]. Rollouts older than a week are compressed by Codex to .jsonl.zst, which cannot be read here without a
     * zstd decoder; those stay reachable through `codex resume` itself.
     */
    fun codexSessions(home: File, cwd: String, limit: Int = MAX_SESSIONS, maxFiles: Int = 600): List<AgentSession> {
        val root = File(home, ".codex/sessions")
        if (!root.isDirectory) return emptyList()
        val index = File(home, ".codex/session_index.jsonl").takeIf { it.isFile }
            ?.let { f -> runCatching { f.useLines { parseCodexSessionIndex(it) } }.getOrNull() }.orEmpty()
        val want = normalizeDir(cwd)
        val out = ArrayList<AgentSession>()
        var scanned = 0
        for (file in newestRollouts(root)) {
            if (out.size >= limit || scanned >= maxFiles) break
            scanned++
            val meta = runCatching { headLines(file, 8).firstNotNullOfOrNull { parseCodexMeta(it) } }.getOrNull() ?: continue
            if (normalizeDir(meta.cwd) != want || !isInteractiveCodexSource(meta.source)) continue
            if (!AgentAdapter.isValidSessionId(meta.id)) continue
            val title = index[meta.id] ?: runCatching { codexFirstPrompt(headLines(file)) }.getOrNull() ?: continue
            out += AgentSession(meta.id, cleanTitle(title), file.lastModified())
        }
        return out.sortedByDescending { it.updatedAt ?: 0L }
    }

    private fun newestRollouts(root: File): Sequence<File> = sequence {
        fun dirs(f: File) = f.listFiles { x -> x.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        for (y in dirs(root)) for (m in dirs(y)) for (d in dirs(m)) {
            val files = d.listFiles { x -> x.isFile && x.name.startsWith("rollout-") && x.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.name }.orEmpty()
            yieldAll(files)
        }
    }

    // ---------------------------------------------------------------- OpenCode

    /**
     * Parses `opencode session list --format json`: `[{"id","title","updated","created","projectId","directory"}]`
     * (packages/cli/src/commands/handlers/session/list.ts, v2.3.0). The list is project-wide, so it is filtered to
     * sessions whose directory equals [cwd]. Non-JSON noise before the array (log lines) is skipped.
     */
    fun parseOpencodeList(stdout: String, cwd: String, limit: Int = MAX_SESSIONS): List<AgentSession> {
        val start = stdout.indexOf('[')
        val end = stdout.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { json.parseToJsonElement(stdout.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
        val want = normalizeDir(cwd)
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val dir = o.str("directory")
            if (dir != null && normalizeDir(dir) != want) return@mapNotNull null
            if (!AgentAdapter.isValidSessionId(id)) return@mapNotNull null
            val updated = (o["updated"] as? JsonPrimitive)?.content?.toLongOrNull()
            AgentSession(id, cleanTitle(o.str("title") ?: "未命名会话"), updated)
        }.sortedByDescending { it.updatedAt ?: 0L }.take(limit)
    }

    // ---------------------------------------------------------------- helpers

    fun normalizeDir(p: String): String = p.trim().trimEnd('/').ifEmpty { "/" }

    fun cleanTitle(raw: String): String {
        val one = raw.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= TITLE_MAX) one else one.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    private fun parseObject(line: String): JsonObject? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /** Reads at most [maxLines] lines / [HEAD_BYTES] bytes from the start of [f]. */
    fun headLines(f: File, maxLines: Int = HEAD_LINES): Sequence<String> {
        val lines = ArrayList<String>()
        BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
            var bytes = 0L
            while (lines.size < maxLines && bytes < HEAD_BYTES) {
                val l = r.readLine() ?: break
                bytes += l.length
                lines += l
            }
        }
        return lines.asSequence()
    }
}
