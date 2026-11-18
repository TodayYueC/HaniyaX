package dev.haniya.x.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Provider switching logic ported from CC Switch (https://github.com/farion1231/cc-switch, MIT License,
 * src-tauri/src/live/floor.rs and src/config/<tool>ProviderPresets.ts). HaniyaX owns only the "key fields"
 * (endpoint, auth, model names, protocol) of each tool's live config; everything else is left untouched.
 * Pure JVM code — unit-tested.
 */

/** Tools whose provider config HaniyaX writes. DSH manages providers in its own settings UI. */
enum class Tool(val id: String, val label: String) {
    Claude("claude", "Claude Code"),
    Codex("codex", "Codex"),
    OpenCode("opencode", "OpenCode"),
    ;

    companion object { fun of(id: String?): Tool? = entries.firstOrNull { it.id == id } }
}

@Serializable
enum class Category { Official, Custom }

/** Claude Code: which env var carries the key. */
@Serializable
enum class ClaudeAuth { AuthToken, ApiKey }

@Serializable
data class Provider(
    val id: String,
    val tool: String,
    val name: String,
    val category: Category = Category.Custom,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** Claude: haiku/small-fast model (optional). */
    val smallModel: String = "",
    val claudeAuth: ClaudeAuth = ClaudeAuth.AuthToken,
    /** Codex: "responses" or "chat". */
    val wireApi: String = "responses",
    /** OpenCode: AI SDK package ("@ai-sdk/openai-compatible" or "@ai-sdk/anthropic"). */
    val npm: String = "@ai-sdk/openai-compatible",
    val websiteUrl: String = "",
    val notes: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class ProviderFile(
    val version: Int = 1,
    val providers: List<Provider> = emptyList(),
    /** tool id → provider id currently written to the live config. */
    val current: Map<String, String> = emptyMap(),
)

/** Key-field lists (CC Switch floor.rs). */
object Floor {
    private val claudeProtocolSelectors = setOf(
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "CLAUDE_CODE_USE_GATEWAY",
        "CLAUDE_CODE_USE_MANTLE", "CLAUDE_CODE_USE_ANTHROPIC_AWS", "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD",
    )
    private val claudeEnvPrefixes = listOf("ANTHROPIC_", "AWS_", "VERTEX_REGION_")
    private val claudeEnvKeys = setOf(
        "CLAUDE_CODE_SUBAGENT_MODEL", "CLOUD_ML_REGION", "GOOGLE_APPLICATION_CREDENTIALS",
        "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_REFRESH_TOKEN", "CLAUDE_CODE_OAUTH_SCOPES",
        "CLAUDE_CODE_API_KEY_HELPER_TTL_MS",
    )
    val claudeTop = setOf(
        "apiKeyHelper", "apiBaseUrl", "primaryModel", "smallFastModel", "apiKey", "model", "fallbackModel",
        "modelOverrides", "modelPicker", "advisorModel", "awsAuthRefresh", "awsCredentialExport", "gcpAuthRefresh",
    )
    /** Provider-specific compatibility switches; removed when switching away. */
    val claudeExclusive = setOf(
        "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "CLAUDE_CODE_DISABLE_ARTIFACT", "ENABLE_TOOL_SEARCH",
        "CLAUDE_CODE_DISABLE_THINKING", "DISABLE_INTERLEAVED_THINKING", "CLAUDE_CODE_ALWAYS_ENABLE_EFFORT",
        "CLAUDE_CODE_EXTRA_BODY", "CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING", "CLAUDE_CODE_SUBAGENT_MODEL_FORCE",
        "CLAUDE_CODE_AUTO_MODE_SERVER", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW",
        "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "CLAUDE_CODE_DISABLE_1M_CONTEXT",
        "CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT", "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY",
    )

    fun claudeEnv(key: String): Boolean =
        claudeEnvPrefixes.any { key.startsWith(it) } || key in claudeProtocolSelectors || key in claudeEnvKeys ||
            (key.startsWith("CLAUDE_CODE_SKIP_") && key.endsWith("_AUTH"))

    val codexTop = listOf(
        "model_provider", "openai_base_url", "model", "review_model", "model_reasoning_effort",
        "plan_mode_reasoning_effort", "disable_response_storage", "model_catalog_json", "experimental_bearer_token",
        "base_url", "wire_api",
    )
    val codexExclusive = listOf("web_search", "model_context_window", "model_auto_compact_token_limit", "model_supports_reasoning_summaries", "model_verbosity")
    const val CODEX_TABLE = "model_providers.custom"
}

/** Claude Code ~/.claude/settings.json. */
object ClaudePatch {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Returns the new file text, or throws IllegalArgumentException when [current] is not a JSON object. */
    fun apply(current: String?, p: Provider): String {
        val root: JsonObject = if (current.isNullOrBlank()) JsonObject(emptyMap()) else {
            val el = runCatching { Json.parseToJsonElement(current) }.getOrElse { throw IllegalArgumentException("settings.json 不是有效的 JSON：${it.message}") }
            el as? JsonObject ?: throw IllegalArgumentException("settings.json 顶层不是对象")
        }
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in root) if (k !in Floor.claudeTop) out[k] = v
        val env = LinkedHashMap<String, JsonElement>()
        (root["env"] as? JsonObject)?.forEach { (k, v) -> if (!Floor.claudeEnv(k) && k !in Floor.claudeExclusive) env[k] = v }
        if (p.category == Category.Custom) {
            if (p.baseUrl.isNotBlank()) env["ANTHROPIC_BASE_URL"] = JsonPrimitive(p.baseUrl.trim())
            if (p.apiKey.isNotBlank()) env[if (p.claudeAuth == ClaudeAuth.ApiKey) "ANTHROPIC_API_KEY" else "ANTHROPIC_AUTH_TOKEN"] = JsonPrimitive(p.apiKey.trim())
            if (p.model.isNotBlank()) {
                env["ANTHROPIC_MODEL"] = JsonPrimitive(p.model.trim())
                env["ANTHROPIC_DEFAULT_SONNET_MODEL"] = JsonPrimitive(p.model.trim())
                env["ANTHROPIC_DEFAULT_OPUS_MODEL"] = JsonPrimitive(p.model.trim())
            }
            val small = p.smallModel.ifBlank { p.model }.trim()
            if (small.isNotBlank()) env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = JsonPrimitive(small)
        }
        if (env.isNotEmpty() || root.containsKey("env")) out["env"] = JsonObject(env)
        return json.encodeToString(JsonElement.serializer(), JsonObject(out)) + "\n"
    }

    data class Effective(val baseUrl: String?, val model: String?, val keyKind: String?, val key: String?)

    fun read(text: String?): Effective {
        val root = runCatching { Json.parseToJsonElement(text ?: "").jsonObject }.getOrNull() ?: return Effective(null, null, null, null)
        val env = root["env"] as? JsonObject
        fun e(k: String) = (env?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val token = e("ANTHROPIC_AUTH_TOKEN"); val apiKey = e("ANTHROPIC_API_KEY")
        return Effective(
            baseUrl = e("ANTHROPIC_BASE_URL"),
            model = e("ANTHROPIC_MODEL") ?: (root["model"] as? JsonPrimitive)?.contentOrNull,
            keyKind = when { token != null -> "ANTHROPIC_AUTH_TOKEN"; apiKey != null -> "ANTHROPIC_API_KEY"; else -> null },
            key = token ?: apiKey,
        )
    }
}

/** Minimal order- and comment-preserving TOML editor for top-level keys and whole tables. */
class TomlDoc(text: String) {
    val lines: MutableList<String> = text.replace("\r\n", "\n").split("\n").toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
    }

    private fun isHeader(l: String) = l.trimStart().startsWith("[")
    private fun headerName(l: String): String? {
        val t = l.trim()
        if (!t.startsWith("[") || t.startsWith("[[")) return null
        return t.removePrefix("[").substringBefore("]").trim().split('.').joinToString(".") { it.trim().trim('"') }
    }
    private fun keyOf(l: String): String? {
        val t = l.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) return null
        val eq = t.indexOf('=')
        if (eq <= 0) return null
        return t.substring(0, eq).trim().trim('"')
    }
    private fun topEnd(): Int = lines.indexOfFirst { isHeader(it) }.let { if (it < 0) lines.size else it }

    fun getTop(key: String): String? = (0 until topEnd()).firstOrNull { keyOf(lines[it]) == key }?.let { valueOf(lines[it]) }

    fun setTop(key: String, tomlValue: String) {
        val end = topEnd()
        val idx = (0 until end).firstOrNull { keyOf(lines[it]) == key }
        val line = "$key = $tomlValue"
        if (idx != null) lines[idx] = line
        else {
            // After the last top-level key line (keeps the top block together), else at the very top.
            val last = (0 until end).lastOrNull { keyOf(lines[it]) != null }
            lines.add(if (last != null) last + 1 else 0, line)
        }
    }

    fun removeTop(key: String) {
        val end = topEnd()
        (0 until end).firstOrNull { keyOf(lines[it]) == key }?.let { lines.removeAt(it) }
    }

    /** Range [start, endExclusive) of table [name] including its header, or null. */
    private fun tableRange(name: String): IntRange? {
        val start = lines.indexOfFirst { headerName(it) == name }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && !isHeader(lines[end])) end++
        // Do not swallow blank lines that separate the next table.
        while (end - 1 > start && lines[end - 1].isBlank()) end--
        return start until end
    }

    fun getTable(name: String): Map<String, String>? {
        val r = tableRange(name) ?: return null
        return r.drop(1).mapNotNull { i -> keyOf(lines[i])?.let { it to (valueOf(lines[i]) ?: "") } }.toMap()
    }

    fun removeTable(name: String) {
        val r = tableRange(name) ?: return
        for (i in r.last downTo r.first) lines.removeAt(i)
        // Collapse a double blank line left behind.
        if (r.first < lines.size && r.first > 0 && lines[r.first].isBlank() && lines[r.first - 1].isBlank()) lines.removeAt(r.first)
    }

    /** Replaces (or appends) table [name] with [entries] (already TOML-encoded values). */
    fun putTable(name: String, entries: List<Pair<String, String>>) {
        val body = listOf("[$name]") + entries.map { (k, v) -> "$k = $v" }
        val r = tableRange(name)
        if (r != null) {
            for (i in r.last downTo r.first) lines.removeAt(i)
            lines.addAll(r.first, body)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.addAll(body)
        }
    }

    override fun toString(): String = lines.joinToString("\n") + "\n"

    companion object {
        fun str(v: String): String = buildString {
            append('"')
            for (ch in v) when (ch) {
                '\\' -> append("\\\\"); '"' -> append("\\\"")
                '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
            append('"')
        }

        /** Decodes a simple TOML value (basic/literal string, bool, number) for display. */
        fun valueOf(line: String): String? {
            val eq = line.indexOf('=')
            if (eq < 0) return null
            var v = line.substring(eq + 1).trim()
            if (v.startsWith("\"")) {
                val sb = StringBuilder(); var i = 1
                while (i < v.length && v[i] != '"') {
                    if (v[i] == '\\' && i + 1 < v.length) { i++; sb.append(when (v[i]) { 'n' -> '\n'; 't' -> '\t'; else -> v[i] }) } else sb.append(v[i])
                    i++
                }
                return sb.toString()
            }
            if (v.startsWith("'")) return v.drop(1).substringBefore("'")
            v = v.substringBefore('#').trim()
            return v
        }
    }
}

/** Codex ~/.codex/config.toml + ~/.codex/auth.json. */
object CodexPatch {
    fun applyConfig(current: String?, p: Provider): String {
        val doc = TomlDoc(current ?: "")
        for (k in Floor.codexTop + Floor.codexExclusive) doc.removeTop(k)
        doc.removeTable(Floor.CODEX_TABLE)
        if (p.category == Category.Custom) {
            doc.setTop("model_provider", TomlDoc.str("custom"))
            if (p.model.isNotBlank()) doc.setTop("model", TomlDoc.str(p.model.trim()))
            doc.putTable(
                Floor.CODEX_TABLE,
                listOf(
                    "name" to TomlDoc.str(p.name),
                    "base_url" to TomlDoc.str(p.baseUrl.trim()),
                    "wire_api" to TomlDoc.str(p.wireApi.ifBlank { "responses" }),
                    "requires_openai_auth" to "true",
                ),
            )
        } else if (p.model.isNotBlank()) {
            doc.setTop("model", TomlDoc.str(p.model.trim()))
        }
        return doc.toString()
    }

    /**
     * auth.json for [p]. For a custom provider: {"OPENAI_API_KEY": key} (other fields such as a ChatGPT login are
     * returned separately so the caller can stash them). For official: [stashedLogin] when available, else the
     * current file without OPENAI_API_KEY.
     */
    fun applyAuth(current: String?, p: Provider, stashedLogin: String?): Pair<String, String?> {
        val cur = runCatching { Json.parseToJsonElement(current ?: "").jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        return if (p.category == Category.Custom) {
            val hasLogin = cur.keys.any { it != "OPENAI_API_KEY" && it != "auth_mode" }
            val stash = if (hasLogin) pretty.encodeToString(JsonElement.serializer(), cur) else null
            pretty.encodeToString(JsonElement.serializer(), buildJsonObject { put("OPENAI_API_KEY", JsonPrimitive(p.apiKey.trim())) }) + "\n" to stash
        } else {
            val restored = stashedLogin ?: pretty.encodeToString(JsonElement.serializer(), JsonObject(cur.filterKeys { it != "OPENAI_API_KEY" }))
            restored.trimEnd() + "\n" to null
        }
    }

    data class Effective(val provider: String?, val model: String?, val baseUrl: String?, val authKind: String?, val key: String?)

    fun read(config: String?, auth: String?): Effective {
        val doc = TomlDoc(config ?: "")
        val prov = doc.getTop("model_provider")
        val table = prov?.let { doc.getTable("model_providers.$it") }
        val a = runCatching { Json.parseToJsonElement(auth ?: "").jsonObject }.getOrNull()
        val key = (a?.get("OPENAI_API_KEY") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val chatgpt = a?.get("tokens") != null
        return Effective(
            provider = prov,
            model = doc.getTop("model"),
            baseUrl = table?.get("base_url") ?: doc.getTop("openai_base_url"),
            authKind = when { key != null -> "OPENAI_API_KEY"; chatgpt -> "chatgpt"; else -> null },
            key = key,
        )
    }
}

/** OpenCode ~/.config/opencode/opencode.json — additive providers, top-level "model". */
object OpenCodePatch {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun providerKey(p: Provider) = "hx-" + p.id.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(32)

    fun apply(current: String?, p: Provider): String {
        val root: JsonObject = if (current.isNullOrBlank()) JsonObject(emptyMap()) else {
            val el = runCatching { Json.parseToJsonElement(current) }.getOrElse {
                throw IllegalArgumentException("opencode.json 无法解析（可能包含注释）：${it.message}。请先手动整理该文件。")
            }
            el as? JsonObject ?: throw IllegalArgumentException("opencode.json 顶层不是对象")
        }
        val out = LinkedHashMap<String, JsonElement>(root)
        if (!out.containsKey("\$schema")) out["\$schema"] = JsonPrimitive("https://opencode.ai/config.json")
        if (p.category == Category.Official) {
            if (p.model.isNotBlank()) out["model"] = JsonPrimitive(p.model.trim()) else out.remove("model")
            return pretty.encodeToString(JsonElement.serializer(), JsonObject(out)) + "\n"
        }
        val key = providerKey(p)
        val providers = LinkedHashMap((root["provider"] as? JsonObject) ?: JsonObject(emptyMap()))
        providers[key] = buildJsonObject {
            put("npm", JsonPrimitive(p.npm))
            put("name", JsonPrimitive(p.name))
            put("options", buildJsonObject {
                put("baseURL", JsonPrimitive(p.baseUrl.trim()))
                if (p.apiKey.isNotBlank()) put("apiKey", JsonPrimitive(p.apiKey.trim()))
            })
            put("models", buildJsonObject { if (p.model.isNotBlank()) put(p.model.trim(), buildJsonObject { put("name", JsonPrimitive(p.model.trim())) }) })
        }
        out["provider"] = JsonObject(providers)
        if (p.model.isNotBlank()) out["model"] = JsonPrimitive("$key/${p.model.trim()}")
        return pretty.encodeToString(JsonElement.serializer(), JsonObject(out)) + "\n"
    }

    data class Effective(val model: String?, val baseUrl: String?, val key: String?)

    fun read(text: String?): Effective {
        val root = runCatching { Json.parseToJsonElement(text ?: "").jsonObject }.getOrNull() ?: return Effective(null, null, null)
        val model = (root["model"] as? JsonPrimitive)?.contentOrNull
        val provKey = model?.substringBefore('/', "")
        val prov = provKey?.let { (root["provider"] as? JsonObject)?.get(it) as? JsonObject }
        val opts = prov?.get("options") as? JsonObject
        return Effective(model, (opts?.get("baseURL") as? JsonPrimitive)?.contentOrNull, (opts?.get("apiKey") as? JsonPrimitive)?.contentOrNull)
    }
}

object Masks {
    fun key(k: String?): String = when {
        k.isNullOrBlank() -> "未设置"
        k.length <= 8 -> "••••"
        else -> "••••" + k.takeLast(4)
    }
}

/** Shell profile lines that export provider env vars (they override the config file for new shells). */
object Overrides {
    private val re = Regex("""^\s*(?:export\s+)?((?:ANTHROPIC|OPENAI|DEEPSEEK|CLAUDE_CODE)_[A-Z0-9_]*)\s*=""")
    fun find(fileName: String, text: String): List<String> =
        text.lineSequence().mapNotNull { l -> re.find(l)?.groupValues?.get(1)?.let { "$fileName：$it" } }.toList()
}

/** Ready-made providers (subset of CC Switch presets, MIT). */
object Presets {
    data class Preset(val name: String, val baseUrl: String, val model: String, val smallModel: String = "", val website: String = "", val wireApi: String = "responses", val npm: String = "@ai-sdk/openai-compatible")

    fun forTool(t: Tool): List<Preset> = when (t) {
        Tool.Claude -> listOf(
            Preset("DeepSeek", "https://api.deepseek.com/anthropic", "deepseek-flash[1M]", website = "https://platform.deepseek.com"),
            Preset("Kimi", "https://api.moonshot.cn/anthropic", "kimi-k3.1-pro", website = "https://platform.kimi.com"),
            Preset("Kimi For Coding", "https://api.kimi.com/coding/", "kimi-for-coding", website = "https://www.kimi.com/code/"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/anthropic", "glm-5.3[1M]", website = "https://open.bigmodel.cn"),
            Preset("MiniMax", "https://api.minimax.cn/anthropic", "MiniMax-M3[1M]", "MiniMax-M3.1-Flash[1M]", "https://platform.minimaxi.com"),
            Preset("SiliconFlow", "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V4-Flash[1M]", website = "https://siliconflow.cn"),
            Preset("OpenRouter", "https://openrouter.ai/api", "anthropic/claude-sonnet-5.5", "anthropic/claude-haiku-5.5", "https://openrouter.ai"),
        )
        Tool.Codex -> listOf(
            Preset("DeepSeek", "https://api.deepseek.com", "deepseek-flash", website = "https://platform.deepseek.com"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/v1", "glm-5.3", website = "https://open.bigmodel.cn"),
            Preset("Kimi", "https://api.moonshot.cn/v1", "kimi-k3.1-pro", website = "https://platform.kimi.com"),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.6", website = "https://openrouter.ai"),
        )
        Tool.OpenCode -> listOf(
            Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", website = "https://platform.deepseek.com"),
            Preset("Kimi", "https://api.moonshot.cn/v1", "kimi-k3.1-pro", website = "https://platform.kimi.com"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3", website = "https://open.bigmodel.cn"),
            Preset("SiliconFlow", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", website = "https://siliconflow.cn"),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5", website = "https://openrouter.ai"),
        )
    }

    fun official(t: Tool): Provider = Provider(
        id = "official-${t.id}", tool = t.id, category = Category.Official,
        name = when (t) { Tool.Claude -> "Anthropic 官方账号"; Tool.Codex -> "OpenAI / ChatGPT 官方账号"; Tool.OpenCode -> "OpenCode 自带登录" },
    )
}

@Suppress("unused")
private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull ?: runCatching { jsonPrimitive.contentOrNull }.getOrNull()
