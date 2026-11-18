package dev.haniya.x.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Plugin manifest (plugin.json), apiVersion 1. Declarative only; rendered natively by the host.
 * The JSON Schema in assets/sdk/docs/plugin.schema.json mirrors this parser exactly.
 * Pure JVM code (unit tested).
 */
const val PLUGIN_API_VERSION = 1

enum class ParamType(val id: String) {
    STRING("string"), NUMBER("number"), PATH("path"), BOOLEAN("boolean");
    companion object { fun of(s: String) = entries.firstOrNull { it.id == s } }
}

data class ParamDef(
    val name: String,
    val type: ParamType,
    val label: String,
    val default: JsonPrimitive?,
    val description: String?,
)

enum class ParseMode(val id: String) { TEXT("text"), JSON("json"), LINES("lines") }

sealed interface SourceDef {
    val name: String
    val type: String

    data class Port(override val name: String, val port: String, val host: String) : SourceDef { override val type get() = "port" }
    data class Http(override val name: String, val url: String, val expectStatus: Int?, val jsonPath: String?) : SourceDef { override val type get() = "http" }
    data class FileSrc(override val name: String, val path: String, val tail: Int?, val maxBytes: Int) : SourceDef { override val type get() = "file" }
    data class Process(override val name: String, val match: String) : SourceDef { override val type get() = "process" }
    data class Command(override val name: String, val run: String, val cwd: String?, val timeoutSec: Int, val parse: ParseMode) : SourceDef { override val type get() = "command" }
}

enum class TextBlockStyle(val id: String) { BODY("body"), CAPTION("caption"), MONO("mono") }

/** okWhen for status blocks. */
sealed interface OkWhen {
    data object Ok : OkWhen
    data object NotOk : OkWhen
    data object NonEmpty : OkWhen
    data object Empty : OkWhen
    data class Contains(val text: String) : OkWhen
    data class Equals(val text: String) : OkWhen
    data class Matches(val regex: Regex) : OkWhen
    data class Gt(val n: Double) : OkWhen
    data class Lt(val n: Double) : OkWhen

    companion object {
        /** Parses "ok", "!ok", "nonempty", "empty", "contains:X", "equals:X", "matches:RE", "gt:N", "lt:N". */
        fun parse(s: String): OkWhen? {
            val t = s.trim()
            return when {
                t == "ok" -> Ok
                t == "!ok" -> NotOk
                t == "nonempty" -> NonEmpty
                t == "empty" -> Empty
                t.startsWith("contains:") -> Contains(t.removePrefix("contains:"))
                t.startsWith("equals:") -> Equals(t.removePrefix("equals:"))
                t.startsWith("matches:") -> runCatching { Regex(t.removePrefix("matches:")) }.getOrNull()?.let { Matches(it) }
                t.startsWith("gt:") -> t.removePrefix("gt:").trim().toDoubleOrNull()?.let { Gt(it) }
                t.startsWith("lt:") -> t.removePrefix("lt:").trim().toDoubleOrNull()?.let { Lt(it) }
                else -> null
            }
        }
    }
}

sealed interface Block {
    data class Status(val source: String, val ok: String, val fail: String, val okWhen: OkWhen) : Block
    data class Text(val value: String, val style: TextBlockStyle) : Block
    data class Kv(val items: List<KvItem>) : Block
    data class Metric(val label: String, val value: String, val unit: String?) : Block
    data class Log(val source: String, val lines: Int) : Block
    data class Progress(val value: String, val label: String?) : Block
    data class Sparkline(val source: String, val label: String?) : Block
}

data class KvItem(val label: String, val value: String)

data class CardDef(val title: String?, val subtitle: String?, val blocks: List<Block>)

enum class ServiceOp(val id: String) { START("start"), STOP("stop"), RESTART("restart") }

sealed interface ActionDef {
    val id: String
    val label: String
    val icon: String?

    data class Sheet(override val id: String, override val label: String, override val icon: String?, val title: String?, val blocks: List<Block>) : ActionDef
    data class Command(override val id: String, override val label: String, override val icon: String?, val run: String, val cwd: String?, val confirm: Boolean, val showOutput: Boolean, val timeoutSec: Int) : ActionDef
    data class Terminal(override val id: String, override val label: String, override val icon: String?, val run: String, val cwd: String?) : ActionDef
    data class OpenUrl(override val id: String, override val label: String, override val icon: String?, val url: String) : ActionDef
    data class Service(override val id: String, override val label: String, override val icon: String?, val name: String, val op: ServiceOp) : ActionDef
}

data class Manifest(
    val apiVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    /** Already resolved: unknown names become "puzzle". */
    val icon: String,
    val params: Map<String, ParamDef>,
    val refreshSec: Int,
    val sources: Map<String, SourceDef>,
    val card: CardDef,
    val actions: List<ActionDef>,
    val tap: String?,
) {
    val trust: PluginTrust get() = Trust.classify(this)
    fun action(id: String?): ActionDef? = actions.firstOrNull { it.id == id }
}

/** Result of parsing; [manifest] is non-null only when [errors] is empty. raw* help render an error card. */
data class ParseResult(
    val manifest: Manifest?,
    val errors: List<String>,
    val warnings: List<String>,
    val rawId: String?,
    val rawName: String?,
    val rawVersion: String?,
    val rawIcon: String?,
) {
    val ok get() = manifest != null
}

object ManifestParser {
    val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    val ACTION_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    val SERVICE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    val LOOPBACK_HOSTS = listOf("127.0.0.1", "localhost", "::1")
    private val LOOPBACK_URL = Regex("^http://(127\\.0\\.0\\.1|localhost|\\[::1])([:/?#]|$)", RegexOption.IGNORE_CASE)

    const val MAX_BLOCKS = 24
    const val MAX_ACTIONS = 8
    const val MAX_SOURCES = 12
    const val MAX_PARAMS = 16
    const val MAX_KV = 12
    const val MAX_MANIFEST_BYTES = 256 * 1024


    fun isLoopbackUrl(url: String): Boolean {
        if (!LOOPBACK_URL.containsMatchIn(url)) return false
        val authority = url.substring(7).takeWhile { it != '/' && it != '?' && it != '#' }
        return '@' !in authority && '\\' !in authority
    }

    /** Runtime check of a rendered URL: scheme http, host loopback, no userinfo. */
    fun isLoopbackRendered(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (!u.scheme.equals("http", ignoreCase = true) || u.rawUserInfo != null) return false
        val h = u.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    fun parse(text: String, expectedId: String? = null): ParseResult {
        if (text.toByteArray().size > MAX_MANIFEST_BYTES) return fail("plugin.json 超过 256 KB")
        val root = try { Json.parseToJsonElement(text) } catch (e: Exception) {
            return fail("plugin.json 不是有效的 JSON：${e.message?.lineSequence()?.firstOrNull()?.take(160) ?: "解析失败"}")
        }
        if (root !is JsonObject) return fail("plugin.json 顶层必须是一个对象 {}")
        return Ctx(expectedId).run(root)
    }

    private fun fail(msg: String) = ParseResult(null, listOf(msg), emptyList(), null, null, null, null)

    /** Collects all errors with JSON paths. */
    private class Ctx(val expectedId: String?) {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun err(path: String, msg: String) { errors.add("$path：$msg") }

        fun run(o: JsonObject): ParseResult {
            allowKeys("$", o, setOf("\$schema", "apiVersion", "id", "name", "version", "description", "icon", "params", "refresh", "sources", "card", "actions", "tap"))

            val apiVersion = when (val a = o["apiVersion"]) {
                null -> { err("apiVersion", "缺少必填字段（当前为 $PLUGIN_API_VERSION）"); PLUGIN_API_VERSION }
                else -> {
                    val v = int(a)
                    if (v == null) { err("apiVersion", "必须是整数"); PLUGIN_API_VERSION }
                    else if (v > PLUGIN_API_VERSION) { err("apiVersion", "此卡片需要更新版本的 HaniyaX（apiVersion $v，本机支持 $PLUGIN_API_VERSION）"); v }
                    else if (v < 1) { err("apiVersion", "必须是 $PLUGIN_API_VERSION"); v }
                    else v
                }
            }
            val id = reqString("id", o["id"])
            if (id != null) {
                if (!ID.matches(id) || id.length > 64) err("id", "只能使用小写字母、数字和单个连字符（kebab-case），最长 64 个字符")
                if (expectedId != null && id != expectedId) err("id", "必须与目录名一致（目录名为 $expectedId）")
            }
            val name = reqString("name", o["name"])?.also { if (it.isBlank()) err("name", "不能为空") else if (it.length > 60) err("name", "最长 60 个字符") }
            val version = reqString("version", o["version"])?.also { if (!SEMVER.matches(it)) err("version", "必须是语义化版本号，例如 1.0.0") }
            val description = optString("description", o["description"])
            val iconRaw = optString("icon", o["icon"])
            val icon = if (iconRaw != null && iconRaw in KnownIcons.names) iconRaw else {
                if (iconRaw != null) warnings.add("icon：未知图标 $iconRaw，已使用 puzzle")
                "puzzle"
            }

            val params = parseParams(o["params"])
            val refresh = o["refresh"]?.let { e ->
                val v = int(e)
                if (v == null) { err("refresh", "必须是整数（秒）"); 15 }
                else if (v < 5) { err("refresh", "最小为 5 秒"); 15 }
                else if (v > 86_400) { err("refresh", "最大为 86400 秒"); 15 }
                else v
            } ?: 15
            val sources = parseSources(o["sources"], params)
            val card = parseCard(o["card"], params, sources)
            val actions = parseActions(o["actions"], params, sources)
            val tap = optString("tap", o["tap"])
            if (tap != null && actions.none { it.id == tap }) err("tap", "没有 id 为 $tap 的动作")

            val manifest = if (errors.isEmpty() && id != null && name != null && version != null && card != null) Manifest(
                apiVersion, id, name, version, description, icon, params, refresh, sources, card, actions, tap,
            ) else null
            return ParseResult(manifest, errors.toList(), warnings.toList(), (o["id"] as? JsonPrimitive)?.contentOrNull, (o["name"] as? JsonPrimitive)?.contentOrNull, (o["version"] as? JsonPrimitive)?.contentOrNull, icon)
        }

        // ---- params ----
        fun parseParams(e: JsonElement?): Map<String, ParamDef> {
            if (e == null) return emptyMap()
            val o = obj("params", e) ?: return emptyMap()
            if (o.size > MAX_PARAMS) err("params", "最多 $MAX_PARAMS 个参数")
            val out = LinkedHashMap<String, ParamDef>()
            for ((k, v) in o) {
                val p = "params.$k"
                if (!KEY.matches(k)) { err(p, "参数名只能使用字母、数字和下划线，且不能以数字开头"); continue }
                val po = obj(p, v) ?: continue
                allowKeys(p, po, setOf("type", "label", "default", "description"))
                val typeS = reqString("$p.type", po["type"]) ?: continue
                val type = ParamType.of(typeS)
                if (type == null) { err("$p.type", "必须是 string、number、path 或 boolean 之一"); continue }
                val label = optString("$p.label", po["label"]) ?: k
                val desc = optString("$p.description", po["description"])
                val def = po["default"]?.let { d ->
                    if (d is JsonNull) null
                    else {
                        val ok = d is JsonPrimitive && when (type) {
                            ParamType.NUMBER -> !d.isString && d.doubleOrNull != null
                            ParamType.BOOLEAN -> !d.isString && d.booleanOrNull != null
                            ParamType.STRING, ParamType.PATH -> d.isString
                        }
                        if (!ok) { err("$p.default", "默认值类型必须是 ${type.id}"); null } else d as JsonPrimitive
                    }
                }
                out[k] = ParamDef(k, type, label, def, desc)
            }
            return out
        }

        // ---- sources ----
        fun parseSources(e: JsonElement?, params: Map<String, ParamDef>): Map<String, SourceDef> {
            if (e == null) return emptyMap()
            val o = obj("sources", e) ?: return emptyMap()
            if (o.size > MAX_SOURCES) err("sources", "最多 $MAX_SOURCES 个数据源")
            val out = LinkedHashMap<String, SourceDef>()
            for ((k, v) in o) {
                val p = "sources.$k"
                if (!KEY.matches(k)) { err(p, "数据源名只能使用字母、数字和下划线，且不能以数字开头"); continue }
                val so = obj(p, v) ?: continue
                val type = reqString("$p.type", so["type"]) ?: continue
                val def: SourceDef? = when (type) {
                    "port" -> {
                        allowKeys(p, so, setOf("type", "port", "host"))
                        val port = when (val pe = so["port"]) {
                            null -> { err("$p.port", "缺少必填字段"); null }
                            is JsonPrimitive -> if (!pe.isString) {
                                val n = int(pe)
                                if (n == null || n !in 1..65535) { err("$p.port", "端口必须是 1 到 65535 之间的整数"); null } else n.toString()
                            } else {
                                val refs = runCatching { Templates.refs(pe.content) }.getOrNull()
                                checkTemplate("$p.port", pe.content, params, emptyMap(), paramsOnly = true)
                                if (refs == null) null
                                else if (refs.isEmpty() && pe.content.trim().toIntOrNull()?.let { it in 1..65535 } != true) {
                                    err("$p.port", "端口必须是 1 到 65535 之间的整数，或 {{params.名称}}"); null
                                } else pe.content
                            }
                            else -> { err("$p.port", "端口必须是整数或 {{params.名称}}"); null }
                        }
                        val host = optString("$p.host", so["host"]) ?: "127.0.0.1"
                        if (host !in LOOPBACK_HOSTS) err("$p.host", "只能是本机地址 127.0.0.1、localhost 或 ::1")
                        port?.let { SourceDef.Port(k, it, host) }
                    }
                    "http" -> {
                        allowKeys(p, so, setOf("type", "url", "expectStatus", "jsonPath"))
                        val url = reqString("$p.url", so["url"])
                        if (url != null) {
                            checkTemplate("$p.url", url, params, emptyMap(), paramsOnly = true)
                            if (!isLoopbackUrl(url)) err("$p.url", "只能访问本机地址，必须以 http://127.0.0.1 或 http://localhost 开头")
                        }
                        val expect = so["expectStatus"]?.let { int(it).also { n -> if (n == null || n !in 100..599) err("$p.expectStatus", "必须是 100 到 599 之间的整数") } }
                        val jp = optString("$p.jsonPath", so["jsonPath"])?.also { if (JsonPath.parse(it) == null) err("$p.jsonPath", "JSON 路径无效，例如 data.items[0].name") }
                        url?.let { SourceDef.Http(k, it, expect, jp) }
                    }
                    "file" -> {
                        allowKeys(p, so, setOf("type", "path", "tail", "maxBytes"))
                        val path = reqString("$p.path", so["path"])
                        if (path != null) {
                            checkTemplate("$p.path", path, params, emptyMap(), paramsOnly = true)
                            if (path.isBlank()) err("$p.path", "不能为空")
                        }
                        val tail = so["tail"]?.let { int(it).also { n -> if (n == null || n !in 1..1000) err("$p.tail", "必须是 1 到 1000 之间的整数") } }
                        val maxBytes = so["maxBytes"]?.let { int(it).also { n -> if (n == null || n !in 1..1_048_576) err("$p.maxBytes", "必须是 1 到 1048576 之间的整数") } } ?: 65_536
                        path?.let { SourceDef.FileSrc(k, it, tail, maxBytes) }
                    }
                    "process" -> {
                        allowKeys(p, so, setOf("type", "match"))
                        val m = reqString("$p.match", so["match"])
                        if (m != null) {
                            if (m.isBlank()) err("$p.match", "不能为空")
                            checkTemplate("$p.match", m, params, emptyMap(), paramsOnly = true)
                        }
                        m?.let { SourceDef.Process(k, it) }
                    }
                    "command" -> {
                        allowKeys(p, so, setOf("type", "run", "cwd", "timeout", "parse"))
                        val run = reqString("$p.run", so["run"])
                        if (run != null) {
                            if (run.isBlank()) err("$p.run", "不能为空")
                            checkCommand("$p.run", run, params)
                        }
                        val cwd = optString("$p.cwd", so["cwd"])?.also { checkCommand("$p.cwd", it, params) }
                        val timeout = so["timeout"]?.let { int(it).also { n -> if (n == null || n !in 1..300) err("$p.timeout", "必须是 1 到 300 之间的整数（秒）") } } ?: 10
                        val parse = optString("$p.parse", so["parse"])?.let { s ->
                            ParseMode.entries.firstOrNull { it.id == s } ?: run { err("$p.parse", "必须是 text、json 或 lines 之一"); ParseMode.TEXT }
                        } ?: ParseMode.TEXT
                        run?.let { SourceDef.Command(k, it, cwd, timeout, parse) }
                    }
                    else -> { err("$p.type", "未知的数据源类型 $type，可用：port、http、file、process、command"); null }
                }
                if (def != null) out[k] = def
            }
            return out
        }

        // ---- card / blocks ----
        fun parseCard(e: JsonElement?, params: Map<String, ParamDef>, sources: Map<String, SourceDef>): CardDef? {
            if (e == null) { err("card", "缺少必填字段"); return null }
            val o = obj("card", e) ?: return null
            allowKeys("card", o, setOf("title", "subtitle", "blocks"))
            val title = optString("card.title", o["title"])?.also { checkTemplate("card.title", it, params, sources) }
            val subtitle = optString("card.subtitle", o["subtitle"])?.also { checkTemplate("card.subtitle", it, params, sources) }
            val blocks = if (o["blocks"] == null) { err("card.blocks", "缺少必填字段"); emptyList() } else parseBlocks("card.blocks", o["blocks"]!!, params, sources)
            return CardDef(title, subtitle, blocks)
        }

        fun parseBlocks(path: String, e: JsonElement, params: Map<String, ParamDef>, sources: Map<String, SourceDef>): List<Block> {
            val arr = e as? JsonArray ?: run { err(path, "必须是数组 []"); return emptyList() }
            if (arr.size > MAX_BLOCKS) err(path, "最多 $MAX_BLOCKS 个展示块")
            val out = ArrayList<Block>()
            arr.forEachIndexed { i, be ->
                val p = "$path[$i]"
                val bo = obj(p, be) ?: return@forEachIndexed
                val type = reqString("$p.type", bo["type"]) ?: return@forEachIndexed
                val b: Block? = when (type) {
                    "status" -> {
                        allowKeys(p, bo, setOf("type", "source", "ok", "fail", "okWhen"))
                        val src = sourceRef("$p.source", bo["source"], sources)
                        val ok = optString("$p.ok", bo["ok"])?.also { checkTemplate("$p.ok", it, params, sources) } ?: "正常"
                        val fail = optString("$p.fail", bo["fail"])?.also { checkTemplate("$p.fail", it, params, sources) } ?: "不可用"
                        val ow = optString("$p.okWhen", bo["okWhen"])?.let { s ->
                            OkWhen.parse(s) ?: run { err("$p.okWhen", "无法识别，可用：ok、!ok、nonempty、empty、contains:文本、equals:文本、matches:正则、gt:数字、lt:数字"); OkWhen.Ok }
                        } ?: OkWhen.Ok
                        src?.let { Block.Status(it, ok, fail, ow) }
                    }
                    "text" -> {
                        allowKeys(p, bo, setOf("type", "value", "style"))
                        val v = reqString("$p.value", bo["value"])?.also { checkTemplate("$p.value", it, params, sources) }
                        val st = optString("$p.style", bo["style"])?.let { s ->
                            TextBlockStyle.entries.firstOrNull { it.id == s } ?: run { err("$p.style", "必须是 body、caption 或 mono 之一"); TextBlockStyle.BODY }
                        } ?: TextBlockStyle.BODY
                        v?.let { Block.Text(it, st) }
                    }
                    "kv" -> {
                        allowKeys(p, bo, setOf("type", "items"))
                        val items = bo["items"]
                        if (items == null) { err("$p.items", "缺少必填字段"); null }
                        else if (items !is JsonArray) { err("$p.items", "必须是数组 []"); null }
                        else {
                            if (items.isEmpty()) err("$p.items", "至少需要一项")
                            if (items.size > MAX_KV) err("$p.items", "最多 $MAX_KV 项")
                            val list = items.mapIndexedNotNull { j, ie ->
                                val ip = "$p.items[$j]"
                                val io = obj(ip, ie) ?: return@mapIndexedNotNull null
                                allowKeys(ip, io, setOf("label", "value"))
                                val l = reqString("$ip.label", io["label"])?.also { checkTemplate("$ip.label", it, params, sources) }
                                val v = reqString("$ip.value", io["value"])?.also { checkTemplate("$ip.value", it, params, sources) }
                                if (l != null && v != null) KvItem(l, v) else null
                            }
                            Block.Kv(list)
                        }
                    }
                    "metric" -> {
                        allowKeys(p, bo, setOf("type", "label", "value", "unit"))
                        val l = reqString("$p.label", bo["label"])?.also { checkTemplate("$p.label", it, params, sources) }
                        val v = reqString("$p.value", bo["value"])?.also { checkTemplate("$p.value", it, params, sources) }
                        val u = optString("$p.unit", bo["unit"])
                        if (l != null && v != null) Block.Metric(l, v, u) else null
                    }
                    "log" -> {
                        allowKeys(p, bo, setOf("type", "source", "lines"))
                        val src = sourceRef("$p.source", bo["source"], sources)
                        val n = bo["lines"]?.let { int(it).also { n -> if (n == null || n !in 1..200) err("$p.lines", "必须是 1 到 200 之间的整数") } } ?: 20
                        src?.let { Block.Log(it, n.coerceIn(1, 200)) }
                    }
                    "progress" -> {
                        allowKeys(p, bo, setOf("type", "value", "label"))
                        val v = reqString("$p.value", bo["value"])?.also { checkTemplate("$p.value", it, params, sources) }
                        val l = optString("$p.label", bo["label"])?.also { checkTemplate("$p.label", it, params, sources) }
                        v?.let { Block.Progress(it, l) }
                    }
                    "sparkline" -> {
                        allowKeys(p, bo, setOf("type", "source", "label"))
                        val src = sourceRef("$p.source", bo["source"], sources)
                        val l = optString("$p.label", bo["label"])?.also { checkTemplate("$p.label", it, params, sources) }
                        src?.let { Block.Sparkline(it, l) }
                    }
                    else -> { err("$p.type", "未知的展示块类型 $type，可用：status、text、kv、metric、log、progress、sparkline"); null }
                }
                if (b != null) out.add(b)
            }
            return out
        }

        // ---- actions ----
        fun parseActions(e: JsonElement?, params: Map<String, ParamDef>, sources: Map<String, SourceDef>): List<ActionDef> {
            if (e == null) return emptyList()
            val arr = e as? JsonArray ?: run { err("actions", "必须是数组 []"); return emptyList() }
            if (arr.size > MAX_ACTIONS) err("actions", "最多 $MAX_ACTIONS 个动作")
            val out = ArrayList<ActionDef>()
            val seen = HashSet<String>()
            arr.forEachIndexed { i, ae ->
                val p = "actions[$i]"
                val ao = obj(p, ae) ?: return@forEachIndexed
                val id = reqString("$p.id", ao["id"])
                if (id != null) {
                    if (!ACTION_ID.matches(id)) err("$p.id", "只能使用字母、数字、- 和 _，最长 40 个字符")
                    if (!seen.add(id)) err("$p.id", "动作 id $id 重复")
                }
                val label = reqString("$p.label", ao["label"])?.also { if (it.isBlank()) err("$p.label", "不能为空") }
                val icon = optString("$p.icon", ao["icon"])?.let { if (it in KnownIcons.names) it else { warnings.add("$p.icon：未知图标 $it，已忽略"); null } }
                val type = reqString("$p.type", ao["type"])
                val common = setOf("id", "label", "icon", "type")
                if (id == null || label == null || type == null) return@forEachIndexed
                val a: ActionDef? = when (type) {
                    "sheet" -> {
                        allowKeys(p, ao, common + setOf("title", "blocks"))
                        val title = optString("$p.title", ao["title"])?.also { checkTemplate("$p.title", it, params, sources) }
                        val blocks = ao["blocks"]?.let { parseBlocks("$p.blocks", it, params, sources) } ?: run { err("$p.blocks", "缺少必填字段"); emptyList() }
                        ActionDef.Sheet(id, label, icon, title, blocks)
                    }
                    "command" -> {
                        allowKeys(p, ao, common + setOf("run", "cwd", "confirm", "showOutput", "timeout"))
                        val run = reqString("$p.run", ao["run"])?.also { if (it.isBlank()) err("$p.run", "不能为空"); checkCommand("$p.run", it, params) }
                        val cwd = optString("$p.cwd", ao["cwd"])?.also { checkCommand("$p.cwd", it, params) }
                        val confirm = optBool("$p.confirm", ao["confirm"]) ?: false
                        val show = optBool("$p.showOutput", ao["showOutput"]) ?: true
                        val timeout = ao["timeout"]?.let { int(it).also { n -> if (n == null || n !in 1..600) err("$p.timeout", "必须是 1 到 600 之间的整数（秒）") } } ?: 60
                        run?.let { ActionDef.Command(id, label, icon, it, cwd, confirm, show, timeout) }
                    }
                    "terminal" -> {
                        allowKeys(p, ao, common + setOf("run", "cwd"))
                        val run = reqString("$p.run", ao["run"])?.also { if (it.isBlank()) err("$p.run", "不能为空"); checkCommand("$p.run", it, params) }
                        val cwd = optString("$p.cwd", ao["cwd"])?.also { checkCommand("$p.cwd", it, params) }
                        run?.let { ActionDef.Terminal(id, label, icon, it, cwd) }
                    }
                    "openUrl" -> {
                        allowKeys(p, ao, common + setOf("url"))
                        val url = reqString("$p.url", ao["url"])?.also {
                            checkTemplate("$p.url", it, params, emptyMap(), paramsOnly = true)
                            if (!isLoopbackUrl(it)) err("$p.url", "只能打开本机地址，必须以 http://127.0.0.1 或 http://localhost 开头")
                        }
                        url?.let { ActionDef.OpenUrl(id, label, icon, it) }
                    }
                    "service" -> {
                        allowKeys(p, ao, common + setOf("name", "op"))
                        val name = reqString("$p.name", ao["name"])?.also { if (!SERVICE_NAME.matches(it)) err("$p.name", "服务名只能使用字母、数字、. _ -") }
                        val op = reqString("$p.op", ao["op"])?.let { s -> ServiceOp.entries.firstOrNull { it.id == s } ?: run { err("$p.op", "必须是 start、stop 或 restart 之一"); null } }
                        if (name != null && op != null) ActionDef.Service(id, label, icon, name, op) else null
                    }
                    else -> { err("$p.type", "未知的动作类型 $type，可用：sheet、command、terminal、openUrl、service"); null }
                }
                if (a != null) out.add(a)
            }
            return out
        }

        // ---- helpers ----
        fun allowKeys(path: String, o: JsonObject, allowed: Set<String>) {
            for (k in o.keys) if (k !in allowed && !k.startsWith("x-")) {
                err(if (path == "$") k else "$path.$k", "未知字段（apiVersion $PLUGIN_API_VERSION 支持：${allowed.filter { it != "\$schema" }.joinToString("、")}；自定义数据请用 x- 前缀）")
            }
        }

        fun obj(path: String, e: JsonElement): JsonObject? = e as? JsonObject ?: run { err(path, "必须是对象 {}"); null }

        fun reqString(path: String, e: JsonElement?): String? {
            if (e == null) { err(path, "缺少必填字段"); return null }
            return str(path, e)
        }

        fun optString(path: String, e: JsonElement?): String? = if (e == null || e is JsonNull) null else str(path, e)

        fun str(path: String, e: JsonElement): String? {
            if (e !is JsonPrimitive || !e.isString) { err(path, "必须是字符串"); return null }
            return e.content
        }

        fun optBool(path: String, e: JsonElement?): Boolean? {
            if (e == null || e is JsonNull) return null
            if (e !is JsonPrimitive || e.isString || e.booleanOrNull == null) { err(path, "必须是 true 或 false"); return null }
            return e.booleanOrNull
        }

        fun int(e: JsonElement): Int? {
            if (e !is JsonPrimitive || e.isString) return null
            val d = e.doubleOrNull ?: return null
            if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return null
            return d.toInt()
        }

        fun sourceRef(path: String, e: JsonElement?, sources: Map<String, SourceDef>): String? {
            val s = reqString(path, e) ?: return null
            if (s !in sources) { err(path, "没有名为 $s 的数据源"); return null }
            return s
        }

        fun checkTemplate(path: String, t: String, params: Map<String, ParamDef>, sources: Map<String, SourceDef>, paramsOnly: Boolean = false) {
            val refs = try { Templates.refs(t) } catch (ex: Templates.TemplateError) { err(path, ex.message ?: "模板无效"); return }
            for (r in refs) when (r.kind) {
                TemplateRef.Kind.Param -> if (r.name !in params) err(path, "引用了未声明的参数 params.${r.name}")
                TemplateRef.Kind.Source -> if (paramsOnly) err(path, "这里只能引用 params，不能引用 sources")
                    else if (r.name !in sources) err(path, "引用了不存在的数据源 sources.${r.name}")
            }
        }

        /** Commands may only reference params, so the approved command text is fixed. */
        fun checkCommand(path: String, t: String, params: Map<String, ParamDef>) {
            val refs = try { Templates.refs(t) } catch (ex: Templates.TemplateError) { err(path, ex.message ?: "模板无效"); return }
            for (r in refs) when (r.kind) {
                TemplateRef.Kind.Param -> if (r.name !in params) err(path, "引用了未声明的参数 params.${r.name}")
                TemplateRef.Kind.Source -> err(path, "命令中只能引用 params，不能引用 sources（确认后的命令必须固定）")
            }
        }
    }
}

/** Icon names available in the Relief icon set (keys of ReliefIcons.paths). Kept here so parsing stays JVM-only. */
object KnownIcons {
    val names: Set<String> = setOf(
        "terminal", "folder", "bot", "server", "plug", "branch", "keyboard", "pin", "puzzle", "cpu", "diff", "paste",
        "history", "zap", "power", "warn", "home", "timer", "chart", "spark", "user", "plus", "minus", "drop", "scale",
        "run", "moon", "check", "habit", "bowl", "pulse", "note", "bell", "layout", "chevR", "chevL", "chevU", "chevD",
        "close", "eyeOff", "eye", "shrink", "expand", "refresh", "send", "arrow", "sliders", "cal", "camera", "image",
        "shield", "shieldAlert", "target", "db", "globe", "help", "info", "clock", "play", "stop", "ruler", "lock",
        "list", "link", "qr", "search", "trash", "edit", "copy", "download", "upload", "drag", "pause", "flame", "heart",
        "logout", "key", "palette",
    )
}
