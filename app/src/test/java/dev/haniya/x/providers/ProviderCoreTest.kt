package dev.haniya.x.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCoreTest {
    private val deepseek = Provider(id = "d1", tool = "claude", name = "DeepSeek", baseUrl = "https://api.deepseek.com/anthropic", apiKey = "sk-123456789", model = "deepseek-flash")

    @Test fun claudeKeepsUnrelatedKeysAndReplacesKeyFields() {
        val before = """{"permissions":{"allow":["Bash"]},"model":"opus","env":{"ANTHROPIC_BASE_URL":"https://old","ANTHROPIC_API_KEY":"old","MY_VAR":"1","CLAUDE_CODE_DISABLE_ARTIFACT":"1"},"theme":"dark"}"""
        val after = Json.parseToJsonElement(ClaudePatch.apply(before, deepseek)).jsonObject
        assertEquals(listOf("permissions", "env", "theme"), after.keys.toList())
        val env = after["env"]!!.jsonObject
        assertEquals("1", env["MY_VAR"]!!.jsonPrimitive.content)
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
        assertFalse(env.containsKey("CLAUDE_CODE_DISABLE_ARTIFACT"))
        assertEquals("https://api.deepseek.com/anthropic", env["ANTHROPIC_BASE_URL"]!!.jsonPrimitive.content)
        assertEquals("sk-123456789", env["ANTHROPIC_AUTH_TOKEN"]!!.jsonPrimitive.content)
        assertEquals("deepseek-flash", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"]!!.jsonPrimitive.content)
    }

    @Test fun claudeOfficialClearsKeyFields() {
        val before = """{"env":{"ANTHROPIC_BASE_URL":"https://x","ANTHROPIC_AUTH_TOKEN":"t","KEEP":"y"}}"""
        val env = Json.parseToJsonElement(ClaudePatch.apply(before, Presets.official(Tool.Claude))).jsonObject["env"]!!.jsonObject
        assertEquals(setOf("KEEP"), env.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeRefusesInvalidJson() { ClaudePatch.apply("{ not json", deepseek) }

    @Test fun claudeEffectiveReadsMaskedKey() {
        val e = ClaudePatch.read(ClaudePatch.apply(null, deepseek))
        assertEquals("https://api.deepseek.com/anthropic", e.baseUrl)
        assertEquals("ANTHROPIC_AUTH_TOKEN", e.keyKind)
        assertEquals("••••6789", Masks.key(e.key))
    }

    @Test fun tomlPreservesCommentsAndOtherTables() {
        val before = """
            # my codex config
            model = "gpt-5"
            approval_policy = "on-request"

            [model_providers.custom]
            name = "Old"
            base_url = "https://old"

            [mcp_servers.fs]
            command = "npx"
        """.trimIndent() + "\n"
        val p = Provider(id = "c1", tool = "codex", name = "DeepSeek", baseUrl = "https://api.deepseek.com", apiKey = "k", model = "deepseek-flash")
        val out = CodexPatch.applyConfig(before, p)
        assertTrue(out.startsWith("# my codex config\n"))
        assertTrue(out.contains("approval_policy = \"on-request\""))
        assertTrue(out.contains("[mcp_servers.fs]\ncommand = \"npx\""))
        assertFalse(out.contains("https://old"))
        val doc = TomlDoc(out)
        assertEquals("custom", doc.getTop("model_provider"))
        assertEquals("deepseek-flash", doc.getTop("model"))
        assertEquals("https://api.deepseek.com", doc.getTable("model_providers.custom")!!["base_url"])
        // Top-level keys must stay above the first table.
        assertTrue(out.indexOf("model_provider") < out.indexOf("[mcp_servers.fs]"))
    }

    @Test fun codexOfficialRemovesCustomTable() {
        val custom = CodexPatch.applyConfig("", Provider(id = "c", tool = "codex", name = "X", baseUrl = "https://x", model = "m"))
        val back = CodexPatch.applyConfig(custom, Presets.official(Tool.Codex))
        assertNull(TomlDoc(back).getTable("model_providers.custom"))
        assertNull(TomlDoc(back).getTop("model_provider"))
    }

    @Test fun codexAuthStashesChatGptLogin() {
        val login = """{"auth_mode":"chatgpt","tokens":{"id_token":"a"},"last_refresh":"x"}"""
        val (auth, stash) = CodexPatch.applyAuth(login, Provider(id = "c", tool = "codex", name = "X", apiKey = "sk-1"), null)
        assertEquals("sk-1", Json.parseToJsonElement(auth).jsonObject["OPENAI_API_KEY"]!!.jsonPrimitive.content)
        assertTrue(stash!!.contains("id_token"))
        val (restored, _) = CodexPatch.applyAuth(auth, Presets.official(Tool.Codex), stash)
        assertTrue(restored.contains("id_token"))
        assertFalse(restored.contains("OPENAI_API_KEY"))
    }

    @Test fun tomlStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\"", TomlDoc.str("a\"b\\c"))
        assertEquals("a\"b", TomlDoc.valueOf("k = \"a\\\"b\""))
    }

    @Test fun opencodeAddsProviderAndModel() {
        val p = Provider(id = "Ds 1", tool = "opencode", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", apiKey = "k", model = "deepseek-flash")
        val out = Json.parseToJsonElement(OpenCodePatch.apply("""{"theme":"x","provider":{"mine":{}}}""", p)).jsonObject
        assertEquals("hx-ds1/deepseek-flash", out["model"]!!.jsonPrimitive.content)
        assertTrue(out["provider"]!!.jsonObject.containsKey("mine"))
        assertTrue(out["provider"]!!.jsonObject.containsKey("hx-ds1"))
        assertEquals("x", out["theme"]!!.jsonPrimitive.content)
    }

    @Test fun overridesDetected() {
        val found = Overrides.find("/root/.bashrc", "alias ll='ls -l'\nexport ANTHROPIC_BASE_URL=https://x\nOPENAI_API_KEY=abc\n")
        assertEquals(listOf("/root/.bashrc：ANTHROPIC_BASE_URL", "/root/.bashrc：OPENAI_API_KEY"), found)
    }
}
