package dev.haniya.x.home

import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtAndTaskListTest {
    @Test fun greetings() {
        assertEquals("早上好", Fmt.greeting(5))
        assertEquals("早上好", Fmt.greeting(10))
        assertEquals("中午好", Fmt.greeting(12))
        assertEquals("下午好", Fmt.greeting(17))
        assertEquals("晚上好", Fmt.greeting(18))
        assertEquals("晚上好", Fmt.greeting(22))
        assertEquals("夜深了", Fmt.greeting(23))
        assertEquals("夜深了", Fmt.greeting(0))
    }

    @Test fun summary() {
        assertEquals("工作区已就绪 · 没有运行中的任务", Fmt.workspaceSummary(0, 0))
        assertEquals("2 个任务运行中 · 0 个服务在线", Fmt.workspaceSummary(2, 0))
        assertEquals("0 个任务运行中 · 1 个服务在线", Fmt.workspaceSummary(0, 1))
    }

    @Test fun durations() {
        assertEquals("0 秒", Fmt.duration(-5))
        assertEquals("45 秒", Fmt.duration(45_000))
        assertEquals("12 分钟", Fmt.duration(12 * 60_000L + 30_000))
        assertEquals("2 小时", Fmt.duration(2 * 3_600_000L))
        assertEquals("2 小时 5 分", Fmt.duration(2 * 3_600_000L + 5 * 60_000))
        assertEquals("3 天 4 小时", Fmt.duration(3 * 86_400_000L + 4 * 3_600_000L))
        assertEquals("刚刚", Fmt.ago(10_000))
        assertEquals("5 分钟前", Fmt.ago(5 * 60_000L))
        assertEquals("2 天前", Fmt.ago(2 * 86_400_000L))
    }

    @Test fun bytes() {
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.5 MB", Fmt.bytes(1024L * 1024 * 3 / 2))
        assertEquals("200 MB", Fmt.bytes(200L * 1024 * 1024))
        assertEquals("2.0 GB", Fmt.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun paths() {
        assertEquals("~", Fmt.folderName("/root"))
        assertEquals("web", Fmt.folderName("/root/projects/web/"))
        assertEquals("/", Fmt.folderName("/"))
        assertEquals("~/projects/web", Fmt.shortPath("/root/projects/web"))
        assertEquals("/rootfs", Fmt.shortPath("/rootfs"))
        assertEquals("/opt/x", Fmt.shortPath("/opt/x"))
    }

    private fun task(id: String, status: TaskStatus, startedAt: Long, cwd: String = "/root/projects/a") =
        TaskRecord(id = id, kind = TaskKind.Shell, title = id, cwd = cwd, startedAt = startedAt, status = status)

    @Test fun homeTaskListShowsRunningThenRecent() {
        val now = 10L * 86_400_000
        val list = listOf(
            task("oldDone", TaskStatus.Exited, now - 2 * 86_400_000),
            task("oldRunning", TaskStatus.Running, now - 3 * 86_400_000),
            task("recentDone", TaskStatus.Exited, now - 3_600_000),
            task("killed", TaskStatus.KilledBySystem, now - 60_000),
            task("newRunning", TaskStatus.Running, now - 1_000),
        )
        assertEquals(listOf("newRunning", "oldRunning", "killed", "recentDone"), TaskList.forHome(list, now).map { it.id })
        assertEquals(2, TaskList.runningCount(list))
    }

    @Test fun statusLabels() {
        assertEquals("运行中", TaskList.statusLabel(task("a", TaskStatus.Running, 0)))
        assertEquals("已结束 · 退出码 1", TaskList.statusLabel(task("a", TaskStatus.Exited, 0).copy(exitCode = 1)))
        assertEquals("已结束", TaskList.statusLabel(task("a", TaskStatus.Exited, 0)))
        assertEquals("已被系统结束", TaskList.statusLabel(task("a", TaskStatus.KilledBySystem, 0)))
    }

    @Test fun projectMembership() {
        assertTrue(TaskList.inProject(task("a", TaskStatus.Running, 0, "/root/projects/web"), "/root/projects/web/"))
        assertTrue(TaskList.inProject(task("a", TaskStatus.Running, 0, "/root/projects/web/src"), "/root/projects/web"))
        assertFalse(TaskList.inProject(task("a", TaskStatus.Running, 0, "/root/projects/website"), "/root/projects/web"))
    }
}
