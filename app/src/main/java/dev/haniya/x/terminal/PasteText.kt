package dev.haniya.x.terminal

/** Pure helpers for pasting and sending text to a terminal (unit-tested). */
object PasteText {
    data class Stats(val chars: Int, val lines: Int)

    const val BRACKET_START = "\u001b[200~"
    const val BRACKET_END = "\u001b[201~"

    private val lineBreak = Regex("\r\n|\r|\n")
    private val unsafe = Regex("[\u001b\u0080-\u009f]")

    /** Characters (code points) and lines; a single trailing line break does not count as an extra line. */
    fun stats(text: String): Stats {
        if (text.isEmpty()) return Stats(0, 0)
        val lines = lines(text)
        return Stats(text.codePointCount(0, text.length), lines.size)
    }

    /** Lines of [text]; a trailing line break does not create an empty last line. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(lineBreak)
        return if (parts.size > 1 && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    fun needsPreview(text: String, maxChars: Int, maxLines: Int): Boolean {
        val s = stats(text)
        return s.chars > maxChars || s.lines > maxLines
    }

    /** The first [max] lines for the preview, each cut to [maxLineChars] characters. */
    fun previewLines(text: String, max: Int = 30, maxLineChars: Int = 240): List<String> =
        lines(text).take(max).map { l -> if (l.length > maxLineChars) l.take(maxLineChars) + "…" else l }

    /**
     * Joins a multi-line text into a single line: lines are trimmed, empty lines dropped and joined with a single
     * space; a shell line continuation (trailing backslash) is removed before joining.
     */
    fun joinAsOneLine(text: String): String =
        lines(text).map { it.trim() }
            .map { if (it.endsWith("\\") && !it.endsWith("\\\\")) it.dropLast(1).trimEnd() else it }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Like the emulator's paste(): removes ESC and C1 controls and turns every line break into a carriage return. */
    fun sanitize(text: String): String = text.replace(unsafe, "").replace(lineBreak, "\r")

    /**
     * Bytes the compose bar writes for [text]: sanitized; a multi-line text is wrapped in bracketed-paste markers
     * when the program enabled bracketed paste (so it is not executed line by line); trailing line breaks are
     * dropped and [enterAfter] adds one Enter at the end.
     */
    fun composeSend(text: String, bracketed: Boolean, enterAfter: Boolean): String {
        val body = sanitize(text).trimEnd('\r')
        val multiLine = body.contains('\r')
        val sb = StringBuilder()
        if (body.isNotEmpty()) {
            if (multiLine && bracketed) sb.append(BRACKET_START).append(body).append(BRACKET_END) else sb.append(body)
        }
        if (enterAfter) sb.append('\r')
        return sb.toString()
    }
}
