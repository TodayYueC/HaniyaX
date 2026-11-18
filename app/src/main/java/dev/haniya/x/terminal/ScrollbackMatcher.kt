package dev.haniya.x.terminal

/**
 * One terminal row as plain text with a cell map: [columns][i] is the screen column where char [i] starts and
 * [widths][i] its width in cells (0 for combining marks and low surrogates, which belong to the char before).
 * [row] uses the emulator's external coordinates (-transcriptRows .. screenRows-1).
 */
class ScreenRow(
    val row: Int,
    val text: String,
    val columns: IntArray,
    val widths: IntArray,
    /** The row is soft-wrapped: its text continues on the next row. */
    val wrapsToNext: Boolean,
) {
    init {
        require(columns.size == text.length && widths.size == text.length) { "cell map must match text length" }
    }

    companion object {
        /** Row of single-width characters starting at column 0 (tests, ASCII output). */
        fun ascii(row: Int, text: String, wrapsToNext: Boolean = false) =
            ScreenRow(row, text, IntArray(text.length) { it }, IntArray(text.length) { 1 }, wrapsToNext)
    }
}

/** A match; columns are inclusive (the end column is the last cell of the last character). */
data class SearchMatch(val startRow: Int, val startColumn: Int, val endRow: Int, val endColumn: Int)

/** Case-insensitive scrollback search over [ScreenRow]s (pure, unit-tested). */
object ScrollbackMatcher {
    const val MAX_MATCHES = 5000

    fun find(rows: List<ScreenRow>, query: String, limit: Int = MAX_MATCHES): List<SearchMatch> {
        if (query.isEmpty() || rows.isEmpty()) return emptyList()
        val needle = fold(query)
        val out = ArrayList<SearchMatch>()
        var i = 0
        while (i < rows.size && out.size < limit) {
            // Collect one logical line: rows joined while they soft-wrap.
            var j = i
            while (j < rows.size - 1 && rows[j].wrapsToNext && rows[j + 1].row == rows[j].row + 1) j++
            searchLine(rows, i, j, needle, out, limit)
            i = j + 1
        }
        return out
    }

    private fun searchLine(rows: List<ScreenRow>, from: Int, to: Int, needle: String, out: MutableList<SearchMatch>, limit: Int) {
        val sb = StringBuilder()
        for (k in from..to) sb.append(rows[k].text)
        val hay = fold(sb.toString())
        if (hay.length < needle.length) return
        // Map a char offset in the logical line back to (row index, char index in row).
        val starts = IntArray(to - from + 2)
        for (k in from..to) starts[k - from + 1] = starts[k - from] + rows[k].text.length
        fun locate(offset: Int): Pair<ScreenRow, Int> {
            var r = 0
            while (r < to - from && offset >= starts[r + 1]) r++
            return rows[from + r] to (offset - starts[r])
        }
        var idx = hay.indexOf(needle)
        while (idx >= 0 && out.size < limit) {
            val (sr, sc) = locate(idx)
            // A match may end on a zero-width tail (low surrogate / combining mark): use the cell of its owner.
            val (er, ec0) = locate(idx + needle.length - 1)
            var ec = ec0
            while (er.widths[ec] == 0 && ec > 0) ec--
            val startCol = sr.columns[firstOwner(sr, sc)]
            val endCol = er.columns[ec] + maxOf(1, er.widths[ec]) - 1
            out += SearchMatch(sr.row, startCol, er.row, endCol)
            idx = hay.indexOf(needle, idx + maxOf(1, needle.length))
        }
    }

    private fun firstOwner(r: ScreenRow, c: Int): Int {
        var k = c
        while (k > 0 && r.widths[k] == 0) k--
        return k
    }

    /** Per-char lower-casing that keeps the string length (so offsets stay aligned). */
    private fun fold(s: String): String {
        val a = CharArray(s.length)
        for (i in s.indices) a[i] = Character.toLowerCase(s[i])
        return String(a)
    }

    /** Index of the match at or nearest after ([row], [column]); last match if none follows; -1 if empty. */
    fun indexAtOrAfter(matches: List<SearchMatch>, row: Int, column: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfFirst { it.startRow > row || (it.startRow == row && it.startColumn >= column) }
        return if (i < 0) matches.lastIndex else i
    }

    /**
     * Top row that shows [m]: unchanged if the match is already fully visible, otherwise the match is placed about a
     * third from the top, clamped to the scrollback range (-[transcriptRows] .. 0).
     */
    fun topRowFor(m: SearchMatch, currentTopRow: Int, screenRows: Int, transcriptRows: Int): Int {
        if (m.startRow >= currentTopRow && m.endRow < currentTopRow + screenRows) return currentTopRow
        val target = m.startRow - screenRows / 3
        return target.coerceIn(-transcriptRows, 0)
    }

    /** Where a row captured at scroll total [then] is now, after the screen scrolled to total [now]. */
    fun shift(m: SearchMatch, then: Long, now: Long): SearchMatch {
        val d = (now - then).toInt()
        return if (d == 0) m else m.copy(startRow = m.startRow - d, endRow = m.endRow - d)
    }
}
