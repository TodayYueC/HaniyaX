package dev.haniya.x.home

import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus

/** Which tasks the home screen shows, and how their state reads (pure, unit-tested). */
object TaskList {
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** Running tasks first, then tasks started within the last 24 h; newest first inside each group. */
    fun forHome(tasks: List<TaskRecord>, now: Long): List<TaskRecord> =
        tasks.filter { it.status == TaskStatus.Running || now - it.startedAt <= WINDOW_MS }
            .sortedWith(compareBy<TaskRecord> { if (it.status == TaskStatus.Running) 0 else 1 }.thenByDescending { it.startedAt })

    fun statusLabel(t: TaskRecord): String = when (t.status) {
        TaskStatus.Running -> "运行中"
        TaskStatus.Exited -> if (t.exitCode != null) "已结束 · 退出码 ${t.exitCode}" else "已结束"
        TaskStatus.KilledBySystem -> "已被系统结束"
    }

    fun inProject(t: TaskRecord, projectPath: String): Boolean {
        val p = projectPath.trimEnd('/')
        return t.cwd == p || t.cwd.startsWith("$p/")
    }

    fun runningCount(tasks: List<TaskRecord>) = tasks.count { it.status == TaskStatus.Running }
}
