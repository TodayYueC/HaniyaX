package dev.haniya.x.dsh

import dev.haniya.relief.theme.ReliefPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshLogicTest {
    @Test fun lastTokenWins() {
        val lines = listOf(
            "Web UI: http://127.0.0.1:3080/?token=old_token-1",
            "some log",
            "Web UI: http://127.0.0.1:3080/?token=NEW.token~2",
        )
        assertEquals("NEW.token~2", DshLogic.lastToken(lines))
        assertNull(DshLogic.lastToken(listOf("listening")))
    }

    @Test fun compareVersions() {
        assertTrue(DshLogic.compare("0.2.10", "0.2.9") > 0)
        assertTrue(DshLogic.compare("0.2.2-rc.3", "0.2.2") < 0)
        assertEquals(0, DshLogic.compare("v1.0.0", "1.0.0"))
    }

    @Test fun cssVarsComplete() {
        val v = DshLogic.cssVars(ReliefPalettes.Platinum)
        assertEquals(16, v.size)
        assertEquals("#e6eaf1", v["--hx-bg"])
        assertTrue(DshLogic.themeScript(v, false).contains("data-theme','light'"))
    }
}
