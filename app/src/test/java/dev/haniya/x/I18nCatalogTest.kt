package dev.haniya.x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** source.txt and en.txt are line-aligned; every translation keeps the same {n} placeholders. */
class I18nCatalogTest {
    private fun lines(name: String) = File("src/main/assets/i18n/$name.txt").readLines(Charsets.UTF_8)
    private val ph = Regex("""(?<!\{)\{\d+\}(?!\})""")

    @Test fun aligned() {
        val s = lines("source"); val e = lines("en")
        assertEquals(s.size, e.size)
        assertEquals("duplicate source lines", s.size, s.toSet().size)
        s.zip(e).forEachIndexed { i, (a, b) ->
            assertEquals("line ${i + 1}: $a", ph.findAll(a).map { it.value }.sorted().toList(), ph.findAll(b).map { it.value }.sorted().toList())
            assertTrue("line ${i + 1} empty", b.isNotBlank())
        }
    }

    @Test fun englishHasNoChinese() {
        val allowed = setOf("简体中文", "繁體中文", "日本語")
        lines("en").forEachIndexed { i, l ->
            if (l in allowed || l.contains("中文 Aa")) return@forEachIndexed
            assertTrue("line ${i + 1}: $l", l.none { it in '㐀'..'鿿' })
        }
    }
}
