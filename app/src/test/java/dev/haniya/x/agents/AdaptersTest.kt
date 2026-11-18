package dev.haniya.x.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptersTest {
    private val prefix = AgentAdapter.PATH_PREFIX

    @Test fun claudeCommands() {
        val a = Adapters.claude
        assertEquals("${prefix}export DISABLE_AUTOUPDATER=1; claude", a.command(null, fullAccess = false))
        assertEquals("${prefix}export DISABLE_AUTOUPDATER=1; claude --continue", a.command("", fullAccess = false))
        assertEquals(
            "${prefix}export DISABLE_AUTOUPDATER=1; claude --resume 0f6c2c8e-7e1b-4b9e-9a51-2a3c4d5e6f70",
            a.command("0f6c2c8e-7e1b-4b9e-9a51-2a3c4d5e6f70", fullAccess = false),
        )
        // Claude has no OS-sandbox switch; full access never adds a flag (and never --dangerously-skip-permissions).
        assertFalse(a.supportsFullAccess)
        assertFalse(a.command(null, fullAccess = true).contains("dangerously"))
    }

    @Test fun codexCommands() {
        val a = Adapters.codex
        assertEquals("${prefix}codex -c check_for_update_on_startup=false", a.command(null, false))
        assertEquals("${prefix}codex -c check_for_update_on_startup=false resume --last", a.command("", false))
        assertEquals(
            "${prefix}codex -c check_for_update_on_startup=false resume 019a-abc --sandbox danger-full-access",
            a.command("019a-abc", true),
        )
        assertTrue(a.supportsFullAccess)
    }

    @Test fun opencodeCommands() {
        val a = Adapters.opencode
        assertEquals("${prefix}export OPENCODE_DISABLE_AUTOUPDATE=1; opencode --standalone --continue", a.command("", false))
        assertEquals("${prefix}export OPENCODE_DISABLE_AUTOUPDATE=1; opencode --standalone --session ses_123abc", a.command("ses_123abc", true))
    }

    @Test fun shellQuoting() {
        assertEquals("abc", AgentAdapter.shQuote("abc"))
        assertEquals("''", AgentAdapter.shQuote(""))
        assertEquals("'a b'", AgentAdapter.shQuote("a b"))
        assertEquals("'it'\\''s'", AgentAdapter.shQuote("it's"))
        assertEquals("'\$(rm -rf /)'", AgentAdapter.shQuote("\$(rm -rf /)"))
    }

    @Test fun sessionIdValidation() {
        assertTrue(AgentAdapter.isValidSessionId("0f6c2c8e-7e1b-4b9e-9a51-2a3c4d5e6f70"))
        assertTrue(AgentAdapter.isValidSessionId("ses_01JABC"))
        assertFalse(AgentAdapter.isValidSessionId("--dangerous"))
        assertFalse(AgentAdapter.isValidSessionId("a b"))
        assertFalse(AgentAdapter.isValidSessionId("x;rm"))
        assertFalse(AgentAdapter.isValidSessionId(""))
    }

    @Test fun jobScriptRunsAdapterFromSdk() {
        val s = Adapters.codex.jobScript("install", "0.169.0")
        assertTrue(s.contains("bash /opt/haniyax/adapters/codex.sh install 0.169.0"))
        // a malformed version is dropped rather than passed to the shell
        assertTrue(Adapters.codex.jobScript("update", "1.0; rm -rf ~").trimEnd().endsWith("codex.sh update"))
    }

    @Test fun detectParser() {
        assertEquals(Detection.Missing, DetectParser.parse("hx:missing\n"))
        val found = DetectParser.parse(
            "hx:path=/root/.local/bin/claude\nhx:real=/root/.local/share/claude/versions/2.1.325\nhx:out-begin\n2.1.325 (Claude Code)\nhx:out-end\n",
        ) as Detection.Found
        assertEquals("/root/.local/bin/claude", found.path)
        assertEquals("2.1.325", found.version)
        assertTrue(Adapters.claude.isManagedPath(found.realPath))
        assertFalse(Adapters.claude.isManagedPath("/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"))
        // Version output that is not a version still counts as installed, version unknown.
        val odd = DetectParser.parse("hx:path=/usr/bin/codex\nhx:real=/usr/bin/codex\nhx:out-begin\nerror: something\nhx:out-end") as Detection.Found
        assertNull(odd.version)
        // Garbage (e.g. the shell failed before our script ran) is not a detection.
        assertNull(DetectParser.parse("bash: fork failed"))
    }

    @Test fun latestSources() {
        assertEquals("2.1.325", DetectParser.latestFromPlain("2.1.325\n"))
        assertNull(DetectParser.latestFromPlain("<html>error</html>"))
        assertEquals("0.169.0", DetectParser.latestFromLocation("https://github.com/openai/codex/releases/tag/rust-v0.169.0", "rust-v"))
        assertNull(DetectParser.latestFromLocation(null, "rust-v"))
        assertNull(DetectParser.latestFromLocation("https://github.com/openai/codex/releases", "rust-v"))
        assertEquals(
            "2.3.0",
            DetectParser.latestFromJson("""{"channel":"latest","name":"cli","version":"2.3.0","active":true}""", "version"),
        )
        assertNull(DetectParser.latestFromJson("""{"version":2}""", "version"))
        assertNull(DetectParser.latestFromJson("not json", "version"))
    }
}
