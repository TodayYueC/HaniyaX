package dev.haniya.x.plugins

import kotlinx.serialization.json.JsonElement

/** The live value of one data source. Pure JVM code. */
data class SourceValue(
    val state: State,
    val ok: Boolean = false,
    val value: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val lines: List<String> = emptyList(),
    val json: JsonElement? = null,
    val status: Int? = null,
    /** Calm, human-readable Chinese error (unreachable file, timeout, …). */
    val error: String? = null,
    val at: Long = System.currentTimeMillis(),
) {
    enum class State { Pending, Ready, Blocked, MissingParams }

    fun field(name: String, jsonPath: String? = null): String = when (state) {
        State.Pending -> "…"
        State.Blocked -> BLOCKED_TEXT
        State.MissingParams -> MISSING_TEXT
        State.Ready -> when (name) {
            "value" -> value
            "ok" -> ok.toString()
            "stdout" -> stdout
            "stderr" -> stderr
            "lines" -> lines.joinToString("\n")
            "status" -> status?.toString() ?: ""
            "error" -> error ?: ""
            "count" -> lines.size.toString()
            "json" -> JsonPath.text(JsonPath.get(json, jsonPath))
            else -> ""
        }
    }

    companion object {
        const val BLOCKED_TEXT = "[需要在 HaniyaX 中确认命令]"
        const val MISSING_TEXT = "[请先填写参数]"
        val PENDING = SourceValue(State.Pending)
        val BLOCKED = SourceValue(State.Blocked, error = "需要在 HaniyaX 中确认命令")
        val MISSING = SourceValue(State.MissingParams, error = "请先填写参数")
        fun failed(msg: String) = SourceValue(State.Ready, ok = false, error = msg)

        /** Splits text into lines, dropping one trailing newline. */
        fun splitLines(s: String): List<String> {
            if (s.isEmpty()) return emptyList()
            val t = s.replace("\r\n", "\n").removeSuffix("\n")
            return t.split('\n')
        }
    }
}

/** Evaluates okWhen against a source value. */
fun OkWhen.test(v: SourceValue): Boolean {
    if (v.state != SourceValue.State.Ready) return false
    val text = v.value.trim()
    return when (this) {
        OkWhen.Ok -> v.ok
        OkWhen.NotOk -> !v.ok
        OkWhen.NonEmpty -> text.isNotEmpty()
        OkWhen.Empty -> text.isEmpty()
        is OkWhen.Contains -> v.value.contains(this.text)
        is OkWhen.Equals -> text == this.text
        is OkWhen.Matches -> regex.containsMatchIn(v.value)
        is OkWhen.Gt -> Numbers.first(text)?.let { it > n } ?: false
        is OkWhen.Lt -> Numbers.first(text)?.let { it < n } ?: false
    }
}

object Numbers {
    private val NUM = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    /** First number found in [s] ("42", "12.5%", "load 0.31"). */
    fun first(s: String): Double? = NUM.find(s)?.value?.toDoubleOrNull()

    /** Last number in a line (e.g. "2025-01-01 37.2" → 37.2). */
    fun last(s: String): Double? = NUM.findAll(s).lastOrNull()?.value?.toDoubleOrNull()

    /** Sparkline series from lines of numbers (last number per line, at most [max] most recent). */
    fun series(lines: List<String>, max: Int = 60): List<Float> =
        lines.mapNotNull { l -> last(l)?.toFloat()?.takeIf { it.isFinite() } }.takeLast(max)

    /** Progress fraction from a rendered template: "0.42", "42%", "42/100". */
    fun fraction(s: String): Float? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val slash = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*$").find(t)
        val f = when {
            slash != null -> {
                val a = slash.groupValues[1].toDouble(); val b = slash.groupValues[2].toDouble()
                if (b == 0.0) return null else a / b
            }
            t.endsWith("%") -> (first(t) ?: return null) / 100.0
            else -> {
                val n = first(t) ?: return null
                if (n > 1.0 && n <= 100.0) n / 100.0 else n
            }
        }
        return f.toFloat().coerceIn(0f, 1f)
    }
}
