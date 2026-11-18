package dev.haniya.x.agents

import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.JobStep
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure helpers behind AgentsScreen and the launch sheet (JVM unit tested). */
object AgentUiLogic {

    /** One-line status under the agent name. */
    fun statusLine(s: AgentStatus?): String = when (s) {
        null, AgentStatus.Unknown, AgentStatus.Checking -> "检查中…"
        AgentStatus.NotInstalled -> "未安装"
        is AgentStatus.Installed -> {
            val v = if (s.version.isBlank()) "已安装 · 版本未知" else "版本 ${s.version}"
            v + if (s.source == AgentManager.SOURCE_HANIYAX) " · 由 HaniyaX 安装" else " · 用户自行安装"
        }
        is AgentStatus.Busy -> if (s.kind == AgentManager.KIND_UPDATE) "正在更新…" else "正在安装…"
        is AgentStatus.Error -> s.message
    }

    /**
     * Overall progress of an install job. Scripts report per-step progress (reset at each `::step::`), so the total is
     * finished steps plus the fraction of the current one, over the adapter's expected step count.
     */
    fun overallProgress(steps: List<JobStep>, stepProgress: Float?, expectedSteps: Int): Float {
        if (steps.isEmpty()) return 0f
        val total = maxOf(expectedSteps, steps.size).coerceAtLeast(1)
        val done = steps.count { it.done }
        val current = if (steps.last().done) 0f else (stepProgress ?: 0f).coerceIn(0f, 1f)
        return ((done + current) / total).coerceIn(0f, 1f)
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** Last [n] non-blank log lines, without colour codes or carriage-return progress redraws. */
    fun tail(log: List<String>, n: Int): List<String> {
        val out = ArrayList<String>(n)
        for (i in log.indices.reversed()) {
            val line = clean(log[i])
            if (line.isBlank()) continue
            out += line
            if (out.size >= n) break
        }
        out.reverse()
        return out
    }

    fun clean(line: String): String = ANSI.replace(line, "").substringAfterLast('\r').trimEnd()

    /** Text put on the clipboard by 复制日志. */
    fun logText(title: String, error: String?, log: List<String>): String = buildString {
        append(title).append('\n')
        if (!error.isNullOrBlank()) append("错误：").append(error).append('\n')
        append('\n')
        log.forEach { append(clean(it)).append('\n') }
    }

    /** "主目录" for /root, "~/projects/web" style otherwise. */
    fun shortPath(path: String): String = when {
        path == Paths.GUEST_HOME -> "~"
        path.startsWith(Paths.GUEST_HOME + "/") -> "~" + path.removePrefix(Paths.GUEST_HOME)
        else -> path
    }

    fun folderName(path: String): String = AgentManager.folderLabel(path)

    /** Calm relative time: 刚刚 / 5 分钟前 / 3 小时前 / 昨天 / 4 天前 / 3月2日 / 2024年3月2日. */
    fun ago(then: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = now - then
        if (d < 60_000L) return "刚刚"
        if (d < 3_600_000L) return "${d / 60_000L} 分钟前"
        val a = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(a, b)
        return when {
            days <= 0L -> "${d / 3_600_000L} 小时前"
            days == 1L -> "昨天"
            days < 7L -> "$days 天前"
            a.year == b.year -> "${a.monthValue}月${a.dayOfMonth}日"
            else -> "${a.year}年${a.monthValue}月${a.dayOfMonth}日"
        }
    }

    /** Agent-launch choices once sessions are known. null = new conversation, "" = continue the most recent, id = that one. */
    fun defaultResume(sessions: List<AgentSession>): String? = if (sessions.isEmpty()) null else ""

    /** Whether an update to [s].latest needs the extra "未经验证" confirmation. */
    fun updateNeedsWarning(s: AgentStatus.Installed): Boolean = s.latest != null && !s.latestVerified
}
