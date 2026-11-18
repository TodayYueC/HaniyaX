package dev.haniya.x.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun claudeProjectDirEncoding() {
        assertEquals("-root", Sessions.claudeProjectDirName("/root"))
        assertEquals("-root-projects-my-app", Sessions.claudeProjectDirName("/root/projects/my.app"))
        assertEquals("-root-projects-hello-world-v2", Sessions.claudeProjectDirName("/root/projects/hello_world v2"))
        // non-ASCII letters are non-alphanumeric for this purpose
        assertEquals("-root-projects---", Sessions.claudeProjectDirName("/root/projects/项目"))
    }

    @Test fun claudeLongNamesAreMatchedByPrefix() {
        val projects = tmp.newFolder("projects")
        val cwd = "/root/" + "a".repeat(250)
        val name = Sessions.claudeProjectDirName(cwd)
        File(projects, name.take(Sessions.CLAUDE_NAME_LIMIT) + "-1a2b3c").mkdirs()
        assertEquals(1, Sessions.claudeProjectDirs(projects, cwd).size)
    }

    private fun claudeLine(type: String, text: String, cwd: String = "/root/projects/web") =
        """{"type":"$type","cwd":"$cwd","sessionId":"s","message":{"role":"$type","content":"$text"}}"""

    @Test fun claudeHeadUsesFirstRealPrompt() {
        val lines = sequenceOf(
            """{"type":"summary","leafUuid":"x"}""",
            """{"type":"user","isMeta":true,"message":{"role":"user","content":"Caveat: meta"}}""",
            claudeLine("user", "<command-name>/clear</command-name>"),
            """{"type":"user","cwd":"/root/projects/web","message":{"role":"user","content":[{"type":"text","text":"修复登录页   的样式\n问题"}]}}""",
            claudeLine("assistant", "ok"),
            claudeLine("user", "second prompt"),
        )
        val head = Sessions.parseClaudeHead(lines)
        assertEquals("修复登录页 的样式 问题", head.title)
        assertEquals("/root/projects/web", head.cwd)
        assertTrue(head.hasUserMessage)
    }

    @Test fun claudeCustomTitleWins() {
        val head = Sessions.parseClaudeHead(sequenceOf(claudeLine("user", "hello"), """{"type":"custom-title","customTitle":"Refactor"}"""))
        assertEquals("Refactor", head.title)
    }

    @Test fun claudeSessionsForProject() {
        val home = tmp.newFolder("home")
        val dir = File(home, ".claude/projects/-root-projects-web").apply { mkdirs() }
        val a = File(dir, "11111111-1111-1111-1111-111111111111.jsonl").apply { writeText(claudeLine("user", "first task") + "\n") }
        val b = File(dir, "22222222-2222-2222-2222-222222222222.jsonl").apply { writeText(claudeLine("user", "second task") + "\n") }
        File(dir, "33333333-3333-3333-3333-333333333333.jsonl").writeText(claudeLine("assistant", "no prompt") + "\n")
        // same encoded dir, different real cwd (/root/projects.web collides) → filtered out
        File(dir, "44444444-4444-4444-4444-444444444444.jsonl").writeText(claudeLine("user", "other", "/root/projects.web") + "\n")
        a.setLastModified(1_000_000L)
        b.setLastModified(2_000_000L)
        val s = Sessions.claudeSessions(home, "/root/projects/web/")
        assertEquals(listOf("22222222-2222-2222-2222-222222222222", "11111111-1111-1111-1111-111111111111"), s.map { it.id })
        assertEquals("second task", s[0].title)
        assertEquals(2_000_000L, s[0].updatedAt)
        assertTrue(Sessions.claudeSessions(home, "/root/projects/none").isEmpty())
    }

    private fun meta(id: String, cwd: String, source: String = "\"cli\"") =
        """{"timestamp":"2026-11-15T10:00:00.000Z","type":"session_meta","payload":{"id":"$id","timestamp":"2026-11-15T10:00:00.000Z","cwd":"$cwd","originator":"codex_cli_rs","cli_version":"0.169.0","source":$source,"base_instructions":{"text":"long"}}}"""

    private fun userMsg(text: String) =
        """{"timestamp":"2026-11-15T10:00:01.000Z","type":"event_msg","payload":{"type":"user_message","message":"$text","images":[]}}"""

    @Test fun codexSessionMeta() {
        val m = Sessions.parseCodexMeta(meta("019a8f2e-aaaa-7bbb-8ccc-0123456789ab", "/root/projects/api"))
        assertNotNull(m)
        assertEquals("019a8f2e-aaaa-7bbb-8ccc-0123456789ab", m!!.id)
        assertEquals("/root/projects/api", m.cwd)
        assertEquals("cli", m.source)
        assertEquals("subagent", Sessions.parseCodexMeta(meta("x", "/r", """{"subagent":{"parent":"y"}}"""))!!.source)
        assertNull(Sessions.parseCodexMeta(userMsg("hi")))
        assertNull(Sessions.parseCodexMeta("not json"))
        assertTrue(Sessions.isInteractiveCodexSource("cli"))
        assertTrue(Sessions.isInteractiveCodexSource(null))
        assertTrue(!Sessions.isInteractiveCodexSource("exec"))
    }

    @Test fun codexSessionsFilteredByCwd() {
        val home = tmp.newFolder("chome")
        val day1 = File(home, ".codex/sessions/2026/11/14").apply { mkdirs() }
        val day2 = File(home, ".codex/sessions/2026/11/15").apply { mkdirs() }
        File(day1, "rollout-2026-11-14T09-00-00-aaaaaaaa-0000-0000-0000-000000000001.jsonl")
            .writeText(meta("aaaaaaaa-0000-0000-0000-000000000001", "/root/projects/api") + "\n" + userMsg("older task") + "\n")
        File(day2, "rollout-2026-11-15T09-00-00-aaaaaaaa-0000-0000-0000-000000000002.jsonl")
            .writeText(meta("aaaaaaaa-0000-0000-0000-000000000002", "/root/projects/api") + "\n" + userMsg("newer task") + "\n")
        File(day2, "rollout-2026-11-15T10-00-00-aaaaaaaa-0000-0000-0000-000000000003.jsonl")
            .writeText(meta("aaaaaaaa-0000-0000-0000-000000000003", "/root/projects/other") + "\n" + userMsg("elsewhere") + "\n")
        File(day2, "rollout-2026-11-15T11-00-00-aaaaaaaa-0000-0000-0000-000000000004.jsonl")
            .writeText(meta("aaaaaaaa-0000-0000-0000-000000000004", "/root/projects/api", "\"exec\"") + "\n" + userMsg("scripted") + "\n")
        File(home, ".codex/session_index.jsonl").writeText(
            """{"id":"aaaaaaaa-0000-0000-0000-000000000001","thread_name":"Old name","updated_at":"x"}""" + "\n" +
                """{"id":"aaaaaaaa-0000-0000-0000-000000000001","thread_name":"Renamed","updated_at":"y"}""" + "\n",
        )
        File(day1, "rollout-2026-11-14T09-00-00-aaaaaaaa-0000-0000-0000-000000000001.jsonl").setLastModified(1_000_000L)
        File(day2, "rollout-2026-11-15T09-00-00-aaaaaaaa-0000-0000-0000-000000000002.jsonl").setLastModified(2_000_000L)
        val s = Sessions.codexSessions(home, "/root/projects/api")
        assertEquals(listOf("aaaaaaaa-0000-0000-0000-000000000002", "aaaaaaaa-0000-0000-0000-000000000001"), s.map { it.id })
        assertEquals("newer task", s[0].title)
        assertEquals("Renamed", s[1].title)
    }

    @Test fun opencodeList() {
        val out = """
            INFO service started
            [
              {"id":"ses_b","title":"Add tests","updated":2000,"created":1500,"projectId":"p","directory":"/root/projects/web"},
              {"id":"ses_a","title":"Fix bug","updated":1000,"created":900,"projectId":"p","directory":"/root/projects/web/"},
              {"id":"ses_c","title":"Other dir","updated":3000,"created":2900,"projectId":"p","directory":"/root/projects/web/sub"}
            ]
        """.trimIndent()
        val s = Sessions.parseOpencodeList(out, "/root/projects/web")
        assertEquals(listOf("ses_b", "ses_a"), s.map { it.id })
        assertEquals("Add tests", s[0].title)
        assertEquals(2000L, s[0].updatedAt)
        assertTrue(Sessions.parseOpencodeList("", "/x").isEmpty())
        assertTrue(Sessions.parseOpencodeList("error: no server", "/x").isEmpty())
    }

    @Test fun titlesAreShortened() {
        val t = Sessions.cleanTitle("x".repeat(200))
        assertEquals(80, t.length)
        assertTrue(t.endsWith("…"))
    }
}
