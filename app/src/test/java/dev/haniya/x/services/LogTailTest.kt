package dev.haniya.x.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogTailTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(text: String): File = tmp.newFile().apply { writeText(text) }

    @Test fun missingOrEmptyFile() {
        assertEquals(emptyList<String>(), LogTail.read(File(tmp.root, "nope.log"), 10))
        assertEquals(emptyList<String>(), LogTail.read(file(""), 10))
        assertEquals(emptyList<String>(), LogTail.read(file("a\n"), 0))
    }

    @Test fun lastLinesWithAndWithoutTrailingNewline() {
        assertEquals(listOf("b", "c"), LogTail.read(file("a\nb\nc\n"), 2))
        assertEquals(listOf("b", "c"), LogTail.read(file("a\nb\nc"), 2))
        assertEquals(listOf("a", "b", "c"), LogTail.read(file("a\nb\nc\n"), 50))
        assertEquals(listOf("only"), LogTail.read(file("only"), 1))
    }

    @Test fun keepsEmptyLinesAndStripsCarriageReturns() {
        assertEquals(listOf("x", "", "y"), LogTail.read(file("x\r\n\r\ny\r\n"), 3))
    }

    @Test fun readsAcrossChunkBoundaries() {
        val lines = (0 until 5000).map { "line $it — 日志 ${"x".repeat(it % 37)}" }
        val f = file(lines.joinToString("\n", postfix = "\n"))
        assertTrue(f.length() > 3 * 8192)
        assertEquals(lines.takeLast(3000), LogTail.read(f, 3000))
        assertEquals(lines.takeLast(1), LogTail.read(f, 1))
        assertEquals(lines, LogTail.read(f, 10_000))
    }

    @Test fun byteCapDropsThePartialFirstLine() {
        val lines = (0 until 10).map { "line$it" } // 6 bytes each incl. newline
        val f = file(lines.joinToString("\n", postfix = "\n"))
        val out = LogTail.read(f, 100, maxBytes = 20)
        assertEquals(listOf("line7", "line8", "line9"), out)
    }

    @Test fun multibyteTextSurvives() {
        val f = file("第一行\n第二行：构建完成 ✓\n")
        assertEquals(listOf("第二行：构建完成 ✓"), LogTail.read(f, 1))
    }

    @Test fun cleansTerminalEscapes() {
        assertEquals("red plain", LogText.clean("\u001B[31mred\u001B[0m plain"))
        assertEquals("100%", LogText.clean("10%\r50%\r100%"))
        assertEquals("title gone", LogText.clean("\u001B]0;my title\u0007title gone"))
        assertEquals("ab", LogText.clean("a\u0008b"))
        assertEquals("tab\tkept", LogText.clean("tab\tkept\r"))
    }
}
