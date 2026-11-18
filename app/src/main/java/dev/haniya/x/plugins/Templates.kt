package dev.haniya.x.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tiny, safe template language for plugin manifests. No code execution, no loops, no filters:
 *
 *   {{params.NAME}}
 *   {{sources.NAME}}                      same as .value
 *   {{sources.NAME.value|ok|stdout|stderr|lines|status|error|count}}
 *   {{sources.NAME.json}} / {{sources.NAME.json.a.b[0].c}}
 *
 * Whitespace inside the braces is allowed. A literal "{{" can be written as "{{'{{'}}".
 * When rendering a shell command, params are substituted shell-quoted (see [ShellQuote]).
 * Pure JVM code (unit tested).
 */
object Templates {
    val SOURCE_FIELDS = setOf("value", "ok", "stdout", "stderr", "lines", "status", "error", "count", "json")
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    sealed interface Seg {
        data class Lit(val text: String) : Seg
        data class Ref(val ref: TemplateRef) : Seg
    }

    class TemplateError(message: String) : Exception(message)

    /** Parses [t] into segments or throws [TemplateError] with a Chinese message. */
    fun parse(t: String): List<Seg> {
        val out = ArrayList<Seg>()
        val lit = StringBuilder()
        var i = 0
        while (i < t.length) {
            val open = t.indexOf("{{", i)
            if (open < 0) { lit.append(t, i, t.length); break }
            lit.append(t, i, open)
            val close = t.indexOf("}}", open + 2)
            if (close < 0) throw TemplateError("模板缺少结尾的 }}（位置 $open）")
            val expr = t.substring(open + 2, close).trim()
            if (expr == "'{{'") {
                lit.append("{{")
            } else {
                if (lit.isNotEmpty()) { out.add(Seg.Lit(lit.toString())); lit.setLength(0) }
                out.add(Seg.Ref(parseRef(expr)))
            }
            i = close + 2
        }
        if (lit.isNotEmpty()) out.add(Seg.Lit(lit.toString()))
        return out
    }

    fun parseRef(expr: String): TemplateRef {
        if (expr.isEmpty()) throw TemplateError("空的模板表达式 {{}}")
        val parts = expr.split('.')
        when (parts[0]) {
            "params" -> {
                if (parts.size != 2 || !NAME.matches(parts[1])) throw TemplateError("无法识别的模板 {{$expr}}，应为 {{params.名称}}")
                return TemplateRef(TemplateRef.Kind.Param, parts[1], null, null)
            }
            "sources" -> {
                if (parts.size < 2 || !NAME.matches(parts[1])) throw TemplateError("无法识别的模板 {{$expr}}，应为 {{sources.名称.字段}}")
                if (parts.size == 2) return TemplateRef(TemplateRef.Kind.Source, parts[1], "value", null)
                val field = parts[2]
                if (field !in SOURCE_FIELDS) throw TemplateError("{{$expr}} 中的字段 $field 不存在，可用：${SOURCE_FIELDS.joinToString("、")}")
                if (field == "json") {
                    val path = if (parts.size > 3) parts.drop(3).joinToString(".") else null
                    if (path != null) JsonPath.parse(path) ?: throw TemplateError("{{$expr}} 中的 JSON 路径无效")
                    return TemplateRef(TemplateRef.Kind.Source, parts[1], "json", path)
                }
                if (parts.size > 3) throw TemplateError("{{$expr}}：只有 json 字段可以继续写路径")
                return TemplateRef(TemplateRef.Kind.Source, parts[1], field, null)
            }
            else -> throw TemplateError("无法识别的模板 {{$expr}}，只能引用 params 或 sources")
        }
    }

    /** All references in [t]; throws [TemplateError] if malformed. */
    fun refs(t: String): List<TemplateRef> = parse(t).mapNotNull { (it as? Seg.Ref)?.ref }

    /**
     * Renders [t]. [shell] = true quotes param values for bash. Source references are resolved by [ctx];
     * malformed templates render literally (validation reports them separately).
     */
    fun render(t: String, ctx: TemplateContext, shell: Boolean = false): String {
        val segs = try { parse(t) } catch (_: TemplateError) { return t }
        val sb = StringBuilder()
        for (s in segs) when (s) {
            is Seg.Lit -> sb.append(s.text)
            is Seg.Ref -> sb.append(resolve(s.ref, ctx, shell))
        }
        return sb.toString()
    }

    private fun resolve(r: TemplateRef, ctx: TemplateContext, shell: Boolean): String = when (r.kind) {
        TemplateRef.Kind.Param -> {
            val p = ctx.params[r.name]
            val type = ctx.paramTypes[r.name] ?: ParamType.STRING
            if (p == null) (if (shell) "''" else "")
            else if (shell) ShellQuote.param(p, type)
            else ParamValues.display(p, type)
        }
        TemplateRef.Kind.Source -> {
            val v = ctx.sources[r.name]
            val text = when {
                v == null -> ""
                else -> v.field(r.field ?: "value", r.jsonPath)
            }
            // Source data never goes into commands (validation forbids it); quote defensively anyway.
            if (shell) ShellQuote.quote(text) else text
        }
    }
}

data class TemplateRef(val kind: Kind, val name: String, val field: String?, val jsonPath: String?) {
    enum class Kind { Param, Source }
}

/** Values available while rendering. */
data class TemplateContext(
    val params: Map<String, JsonPrimitive> = emptyMap(),
    val paramTypes: Map<String, ParamType> = emptyMap(),
    val sources: Map<String, SourceValue> = emptyMap(),
)

object ShellQuote {
    /** POSIX single-quote quoting; always safe for bash. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Quotes a param value for a bash command. Numbers/booleans are validated, so they stay bare. */
    fun param(v: JsonPrimitive, type: ParamType): String = when (type) {
        ParamType.NUMBER -> ParamValues.numberText(v)?.let { n -> if (SAFE_NUM.matches(n)) n else quote(n) } ?: "''"
        ParamType.BOOLEAN -> if (ParamValues.bool(v) == true) "true" else "false"
        ParamType.PATH -> {
            val s = v.content
            when {
                s == "~" -> "\"\$HOME\""
                s.startsWith("~/") -> "\"\$HOME\"" + quote(s.substring(1))
                else -> quote(s)
            }
        }
        ParamType.STRING -> quote(v.content)
    }

    private val SAFE_NUM = Regex("^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
}

/** Helpers for typed param values. */
object ParamValues {
    fun numberText(v: JsonPrimitive): String? {
        val d = v.content.trim().toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    fun bool(v: JsonPrimitive): Boolean? = when (v.content.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    fun display(v: JsonPrimitive, type: ParamType): String = when (type) {
        ParamType.NUMBER -> numberText(v) ?: v.content
        ParamType.BOOLEAN -> (bool(v) ?: false).toString()
        ParamType.PATH -> expandHome(v.content)
        ParamType.STRING -> v.content
    }

    /** Guest path expansion of a leading ~ (guest HOME is /root). */
    fun expandHome(p: String): String = when {
        p == "~" -> "/root"
        p.startsWith("~/") -> "/root" + p.substring(1)
        else -> p
    }

    /** Coerces a JSON value to [type]; null when it is not a valid value of that type. */
    fun coerce(e: JsonElement?, type: ParamType): JsonPrimitive? {
        if (e == null || e is JsonNull || e !is JsonPrimitive) return null
        return when (type) {
            ParamType.NUMBER -> {
                val d = e.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            ParamType.BOOLEAN -> bool(e)?.let { JsonPrimitive(it) }
            ParamType.STRING, ParamType.PATH -> if (e.isString || e.content.isNotEmpty()) JsonPrimitive(e.content) else null
        }
    }
}

/** Minimal JSON path: `a.b[0].c` or `a.b.0.c`. */
object JsonPath {
    fun parse(path: String): List<Any>? {
        if (path.isBlank()) return emptyList()
        val out = ArrayList<Any>()
        for (raw in path.split('.')) {
            if (raw.isEmpty()) return null
            var p = raw
            val b = p.indexOf('[')
            val head = if (b >= 0) p.substring(0, b) else p
            if (head.isNotEmpty()) out.add(head.toIntOrNull()?.takeIf { head.all(Char::isDigit) } ?: head)
            p = if (b >= 0) p.substring(b) else ""
            while (p.isNotEmpty()) {
                if (!p.startsWith("[")) return null
                val e = p.indexOf(']')
                if (e < 0) return null
                val idx = p.substring(1, e).toIntOrNull() ?: return null
                if (idx < 0) return null
                out.add(idx)
                p = p.substring(e + 1)
            }
        }
        return out
    }

    fun get(root: JsonElement?, path: String?): JsonElement? {
        if (root == null) return null
        if (path == null) return root
        val segs = parse(path) ?: return null
        var cur: JsonElement = root
        for (s in segs) {
            cur = when {
                s is Int && cur is JsonArray -> cur.getOrNull(s) ?: return null
                s is Int && cur is JsonObject -> cur[s.toString()] ?: return null
                s is String && cur is JsonObject -> cur[s] ?: return null
                else -> return null
            }
        }
        return cur
    }

    /** Text form of a JSON value: strings unquoted, everything else compact JSON. */
    fun text(e: JsonElement?): String = when (e) {
        null, is JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> e.toString()
    }
}
