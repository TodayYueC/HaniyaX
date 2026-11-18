package dev.haniya.x.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkHintsTest {
    private val block = SdkHints.block

    @Test fun insertsIntoEmptyFile() {
        assertEquals("$block\n", SdkHints.apply(""))
    }

    @Test fun appendsAfterUserContentWithBlankLine() {
        assertEquals("# Mine\nrules\n\n$block\n", SdkHints.apply("# Mine\nrules\n"))
        assertEquals("# Mine\n\n$block\n", SdkHints.apply("# Mine"))
        assertEquals("# Mine\n\n\n$block\n", SdkHints.apply("# Mine\n\n"))
    }

    @Test fun isIdempotent() {
        for (orig in listOf("", "a", "a\n", "a\n\n", "# T\n\nbody\n")) {
            val once = SdkHints.apply(orig)
            assertEquals(once, SdkHints.apply(once))
            assertTrue(SdkHints.contains(once))
        }
    }

    @Test fun replacesAnOutdatedBlockInPlace() {
        val old = "top\n${SdkHints.BEGIN}\nold text\n${SdkHints.END}\nbottom\n"
        assertEquals("top\n$block\nbottom\n", SdkHints.apply(old))
    }

    @Test fun removeRestoresOriginal() {
        for (orig in listOf("", "a\n", "# T\n\nbody\n", "x\n\n", "x\n\n\n")) {
            assertEquals(orig, SdkHints.remove(SdkHints.apply(orig)))
        }
        // Only a missing final newline is normalised.
        assertEquals("a\n", SdkHints.remove(SdkHints.apply("a")))
    }

    @Test fun removeKeepsSurroundingContent() {
        val text = "top\n${SdkHints.BEGIN}\nold\n${SdkHints.END}\nbottom\n"
        assertEquals("top\nbottom\n", SdkHints.remove(text))
    }

    @Test fun removeWithoutBlockIsNoop() {
        val text = "nothing here\n"
        assertEquals(text, SdkHints.remove(text))
        assertFalse(SdkHints.contains(text))
        // A lone begin marker without an end is not ours to edit.
        val broken = "x\n${SdkHints.BEGIN}\ny\n"
        assertEquals(broken, SdkHints.remove(broken))
    }

    @Test fun blockPointsAgentsAtTheSdk() {
        assertTrue(SdkHints.BODY.contains("hx"))
        assertTrue(SdkHints.BODY.contains("/opt/haniyax/docs/README.md"))
    }
}
