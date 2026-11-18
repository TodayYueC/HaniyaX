package dev.haniya.x.terminal

import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus

/** Pure helpers for the tabs bar and the ended-task overlay (unit-tested). */
object TabLabels {
    /** Prefix TaskManager puts into lastTitle when a task could not start. */
    const val START_FAILED_PREFIX = "无法启动："

    /** Short folder name for a guest directory: "~" for home, last path segment otherwise. */
    fun folder(cwd: String): String {
        val p = cwd.trimEnd('/')
        return when {
            p.isEmpty() -> "/"
            p == Paths.GUEST_HOME -> "~"
            else -> p.substringAfterLast('/').ifEmpty { p }
        }
    }

    /** "Claude Code · web" for agents, "Shell · web" for shells. */
    fun title(rec: TaskRecord, agentName: String?): String {
        val who = when (rec.kind) {
            TaskKind.Agent -> agentName ?: rec.agentId ?: rec.title
            TaskKind.Shell -> "Shell"
        }
        return "$who · ${folder(rec.cwd)}"
    }

    /** Tabs in a stable order (oldest first) so they do not jump when a task's status changes. */
    fun openTabs(tasks: List<TaskRecord>): List<TaskRecord> =
        tasks.filter { it.tabOpen }.sortedWith(compareBy<TaskRecord> { it.startedAt }.thenBy { it.id })

    /** The tab to show: [focused] if it has an open tab, else the first open tab. */
    fun pick(openTabs: List<TaskRecord>, focused: String?): String? =
        openTabs.firstOrNull { it.id == focused }?.id ?: openTabs.firstOrNull()?.id

    data class Ended(val title: String, val detail: String)

    /** Real status of a task that is not running, for the overlay; null while it runs. */
    fun ended(rec: TaskRecord): Ended? = when (rec.status) {
        TaskStatus.Running -> null
        TaskStatus.KilledBySystem -> Ended(
            "已被系统结束（Android 回收了进程）",
            "应用在后台时被系统回收，终端里的程序也随之结束。屏幕上的输出无法找回。",
        )
        TaskStatus.Exited -> {
            val failed = rec.lastTitle?.takeIf { it.startsWith(START_FAILED_PREFIX) }
            when {
                failed != null -> Ended("无法启动", failed.removePrefix(START_FAILED_PREFIX))
                rec.exitCode != null -> Ended("进程已结束 · 退出码 ${rec.exitCode}", exitHint(rec.exitCode))
                else -> Ended("进程已结束", "程序已经退出。")
            }
        }
    }

    private fun exitHint(code: Int): String = when {
        code == 0 -> "程序正常退出。"
        code == 130 -> "程序被 Ctrl+C 中断。"
        code > 128 -> "程序被信号 ${code - 128} 结束。"
        else -> "程序以非零状态退出，上方是它最后的输出。"
    }
}
