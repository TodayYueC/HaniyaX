package dev.haniya.x.agents

import dev.haniya.x.runtime.JobStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AgentUiLogicTest {
    @Test fun statusLines() {
        assertEquals("检查中…", AgentUiLogic.statusLine(null))
        assertEquals("未安装", AgentUiLogic.statusLine(AgentStatus.NotInstalled))
        assertEquals("版本 2.1.325 · 由 HaniyaX 安装", AgentUiLogic.statusLine(AgentStatus.Installed("2.1.325", "/p", "haniyax", null, false)))
        assertEquals("版本 0.169.0 · 用户自行安装", AgentUiLogic.statusLine(AgentStatus.Installed("0.169.0", "/p", "user", null, false)))
        assertEquals("已安装 · 版本未知 · 用户自行安装", AgentUiLogic.statusLine(AgentStatus.Installed("", "/p", "user", null, false)))
        assertEquals("正在更新…", AgentUiLogic.statusLine(AgentStatus.Busy("j", "update")))
    }

    @Test fun overallProgress() {
        assertEquals(0f, AgentUiLogic.overallProgress(emptyList(), null, 5), 0.001f)
        val steps = listOf(JobStep("a", true), JobStep("b", true), JobStep("c", false))
        assertEquals((2f + 0.5f) / 5f, AgentUiLogic.overallProgress(steps, 0.5f, 5), 0.001f)
        // more steps than expected never exceeds 1
        val many = List(7) { JobStep("s$it", true) }
        assertEquals(1f, AgentUiLogic.overallProgress(many, null, 5), 0.001f)
    }

    @Test fun tailSkipsBlankAndColour() {
        val log = listOf("one", "", "\u001B[32mtwo\u001B[0m", "  ", "10%\r50%\r100%", "four")
        assertEquals(listOf("two", "100%", "four"), AgentUiLogic.tail(log, 3))
        assertEquals(listOf("one", "two", "100%", "four"), AgentUiLogic.tail(log, 10))
    }

    @Test fun relativeTime() {
        val z = ZoneId.of("Asia/Shanghai")
        fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()
        val now = t(2026, 11, 15, 12, 0)
        assertEquals("刚刚", AgentUiLogic.ago(now - 10_000, now, z))
        assertEquals("5 分钟前", AgentUiLogic.ago(now - 5 * 60_000, now, z))
        assertEquals("3 小时前", AgentUiLogic.ago(t(2026, 11, 15, 9, 0), now, z))
        assertEquals("昨天", AgentUiLogic.ago(t(2026, 11, 14, 23, 0), now, z))
        assertEquals("4 天前", AgentUiLogic.ago(t(2026, 11, 11, 8, 0), now, z))
        assertEquals("3月2日", AgentUiLogic.ago(t(2026, 3, 2, 8, 0), now, z))
        assertEquals("2025年12月31日", AgentUiLogic.ago(t(2025, 12, 31, 8, 0), now, z))
        // across a year boundary "yesterday" still reads 昨天
        assertEquals("昨天", AgentUiLogic.ago(t(2025, 12, 31, 22, 0), t(2026, 1, 1, 9, 0), z))
    }

    @Test fun paths() {
        assertEquals("~", AgentUiLogic.shortPath("/root"))
        assertEquals("~/projects/web", AgentUiLogic.shortPath("/root/projects/web"))
        assertEquals("/srv/x", AgentUiLogic.shortPath("/srv/x"))
        assertEquals("主目录", AgentUiLogic.folderName("/root"))
        assertEquals("web", AgentUiLogic.folderName("/root/projects/web/"))
    }

    @Test fun resumeDefaultsAndWarnings() {
        assertNull(AgentUiLogic.defaultResume(emptyList()))
        assertEquals("", AgentUiLogic.defaultResume(listOf(AgentSession("a", "t", 1L))))
        assertTrue(AgentUiLogic.updateNeedsWarning(AgentStatus.Installed("0.169.0", "/p", "haniyax", "1.0.0", false)))
        assertTrue(!AgentUiLogic.updateNeedsWarning(AgentStatus.Installed("0.169.0", "/p", "haniyax", "0.170.0", true)))
    }
}
