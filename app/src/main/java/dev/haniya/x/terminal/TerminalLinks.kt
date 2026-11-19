package dev.haniya.x.terminal

/**
 * Finds the web link under a tap in the terminal. Sign-in URLs printed by agents are long and span several rows,
 * either soft-wrapped by the terminal ([ScreenRow.wrapsToNext]) or hard-wrapped by the program at the right edge
 * (a row filled up to the last columns, continued on the next row). Both are joined before matching.
 */
object TerminalLinks {
    private val URL = Regex("""(?:https?|file)://[^\s<>"'`│┃|]+""")
    private const val MAX_ROWS = 24
    private const val TRAILING = ".,;:!?'\""

    /** [rows] in order, [index] the tapped row, [column] the tapped cell, [cols] the terminal width. */
    fun urlAt(rows: List<ScreenRow>, index: Int, column: Int, cols: Int): String? {
        val tapped = rows.getOrNull(index) ?: return null
        val tapChar = charAt(tapped, column) ?: return null

        var first = index
        while (first > 0 && index - first < MAX_ROWS && continues(rows[first - 1], rows[first], cols)) first--
        var last = index
        while (last < rows.lastIndex && last - index < MAX_ROWS && continues(rows[last], rows[last + 1], cols)) last++

        val sb = StringBuilder()
        var tapPos = -1
        for (i in first..last) {
            val r = rows[i]
            val soft = i > first && rows[i - 1].wrapsToNext
            var start = if (i == first || soft) 0 else r.text.indexOfFirst { it != ' ' }.coerceAtLeast(0)
            val end = if (i == last || r.wrapsToNext) r.text.length else r.text.trimEnd().length
            if (start > end) start = end
            if (i == index) tapPos = sb.length + (tapChar - start)
            sb.append(r.text, start, end)
        }
        if (tapPos < 0) return null
        val line = sb.toString()
        for (m in URL.findAll(line)) {
            val url = trim(m.value)
            if (tapPos >= m.range.first && tapPos < m.range.first + url.length) return url.takeIf { it.length > 10 }
        }
        return null
    }

    /** Index in [r].text of the character drawn at [column], or null for empty space. */
    private fun charAt(r: ScreenRow, column: Int): Int? {
        for (i in r.text.indices) {
            val w = maxOf(r.widths[i], 1)
            if (r.widths[i] > 0 && column >= r.columns[i] && column < r.columns[i] + w) return i
        }
        return null
    }

    private fun continues(a: ScreenRow, b: ScreenRow, cols: Int): Boolean {
        if (a.wrapsToNext) return true
        val t = a.text.trimEnd()
        if (t.isEmpty()) return false
        val lastCol = a.columns[t.lastIndex] + maxOf(a.widths[t.lastIndex], 1)
        if (lastCol < cols - 2) return false
        // Hard wrap: the row ends inside a URL-looking token and the next row starts with one.
        val tail = t.substringAfterLast(' ')
        val head = b.text.trimStart().substringBefore(' ')
        return tail.isNotEmpty() && head.isNotEmpty() && tail.all(::urlChar) && head.all(::urlChar) &&
            urlish(tail) && urlish(head)
    }

    /** A token that could be a piece of a URL rather than an ordinary word. */
    private fun urlish(t: String) = URL.containsMatchIn(t) || t.any { it in "/?=&%#._-~+" } ||
        (t.length >= 8 && t.any(Char::isDigit) && t.any(Char::isLetter))

    private fun urlChar(c: Char) = c.code in 0x21..0x7e && c !in "<>\"'`|"

    private fun trim(u: String): String {
        var s = u
        while (s.isNotEmpty() && s.last() in TRAILING) s = s.dropLast(1)
        // Drop a closing bracket that has no opening one inside the URL ("(see https://x.y/z)").
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            while (s.endsWith(close) && s.count { it == close } > s.count { it == open }) s = s.dropLast(1)
        }
        return s
    }
}
