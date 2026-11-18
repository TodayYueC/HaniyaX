package dev.haniya.x.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecordsTest {
    private fun rec(id: String, startedAt: Long, status: TaskStatus = TaskStatus.Running, kind: TaskKind = TaskKind.Shell, tabOpen: Boolean = true) =
        TaskRecord(id = id, kind = kind, title = id, cwd = "/root", startedAt = startedAt, status = status, tabOpen = tabOpen)

    @Test
    fun `running first, then newest`() {
        val list = listOf(
            rec("old-exited", 1, TaskStatus.Exited),
            rec("old-running", 2),
            rec("new-exited", 5, TaskStatus.KilledBySystem),
            rec("new-running", 4),
        )
        assertEquals(listOf("new-running", "old-running", "new-exited", "old-exited"), TaskRecords.sorted(list).map { it.id })
    }

    @Test
    fun `restore marks running as killed by system`() {
        val restored = TaskRecords.restore(listOf(rec("a", 1), rec("b", 2, TaskStatus.Exited)))
        assertEquals(TaskStatus.KilledBySystem, restored[0].status)
        assertEquals(TaskStatus.Exited, restored[1].status)
    }

    @Test
    fun `records round-trip and corrupt files decode empty`() {
        val list = listOf(
            rec("a", 1).copy(command = "claude", agentId = "claude", resumeCommand = "claude --continue", lastTitle = "~/p"),
            rec("b", 2, TaskStatus.Exited).copy(exitCode = 130, tabOpen = false),
        )
        assertEquals(list, TaskRecords.decode(TaskRecords.encode(list)))
        assertTrue(TaskRecords.decode(null).isEmpty())
        assertTrue(TaskRecords.decode("garbage").isEmpty())
    }

    @Test
    fun `counts running tasks and agents`() {
        val c = TaskRecords.counts(
            listOf(rec("a", 1), rec("b", 3, kind = TaskKind.Agent), rec("c", 9, TaskStatus.Exited)),
        )
        assertEquals(TaskCounts(running = 2, runningAgents = 1, latestRunningId = "b"), c)
        assertEquals(TaskCounts(), TaskRecords.counts(emptyList()))
    }

    @Test
    fun `next focus prefers a running open tab`() {
        val list = listOf(
            rec("closed", 9, tabOpen = false),
            rec("exited-open", 8, TaskStatus.Exited),
            rec("running-open", 2),
            rec("current", 5),
        )
        assertEquals("running-open", TaskRecords.nextFocus(list, "current"))
        assertEquals("exited-open", TaskRecords.nextFocus(list.filter { it.id != "running-open" }, "current"))
        assertNull(TaskRecords.nextFocus(listOf(rec("current", 1)), "current"))
    }
}
