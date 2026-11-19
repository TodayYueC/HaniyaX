package dev.haniya.x.agents

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentThemesTest {
    @Test fun writesSystemThemeWhenMissing() {
        val fresh = AgentThemes.openCodeCliJson(null)!!
        assertTrue(fresh.contains("\"name\": \"system\""))
        val merged = AgentThemes.openCodeCliJson("""{"keybinds":{"leader":"ctrl+x"}}""")!!
        assertTrue(merged.contains("leader") && merged.contains("\"system\""))
    }

    @Test fun keepsUserThemeAndUnparsableFiles() {
        assertNull(AgentThemes.openCodeCliJson("""{"theme":{"name":"tokyonight"}}"""))
        assertNull(AgentThemes.openCodeCliJson("// comment\n{ }"))
    }
}
