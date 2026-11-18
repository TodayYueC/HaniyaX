package dev.haniya.x.home

import dev.haniya.x.core.Paths
import java.util.Locale
import kotlin.math.roundToLong

/** Calm Chinese formatting helpers (pure, unit-tested). */
object Fmt {
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> "早上好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

    /** Header caption on the home screen. */
    fun workspaceSummary(runningTasks: Int, onlineServices: Int): String =
        if (runningTasks <= 0 && onlineServices <= 0) "工作区已就绪 · 没有运行中的任务"
        else "${runningTasks.coerceAtLeast(0)} 个任务运行中 · ${onlineServices.coerceAtLeast(0)} 个服务在线"

    /** Elapsed time, e.g. "45 秒", "12 分钟", "2 小时 5 分", "3 天 4 小时". */
    fun duration(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "$s 秒"
            s < 3600 -> "${s / 60} 分钟"
            s < 86_400 -> {
                val h = s / 3600; val m = (s % 3600) / 60
                if (m == 0L) "$h 小时" else "$h 小时 $m 分"
            }
            else -> {
                val d = s / 86_400; val h = (s % 86_400) / 3600
                if (h == 0L) "$d 天" else "$d 天 $h 小时"
            }
        }
    }

    /** Relative past time, e.g. "刚刚", "5 分钟前". */
    fun ago(ms: Long): String = when {
        ms < 60_000 -> "刚刚"
        ms < 3_600_000 -> "${ms / 60_000} 分钟前"
        ms < 86_400_000 -> "${ms / 3_600_000} 小时前"
        else -> "${ms / 86_400_000} 天前"
    }

    fun bytes(n: Long): String {
        if (n < 1024) return "${n.coerceAtLeast(0)} B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) "${v.roundToLong()} ${units[i]}" else String.format(Locale.ROOT, "%.1f %s", v, units[i])
    }

    /** Last path segment for display; the guest home shows as "~". */
    fun folderName(path: String): String {
        val p = path.trimEnd('/')
        if (p.isEmpty()) return "/"
        if (p == Paths.GUEST_HOME) return "~"
        return p.substringAfterLast('/')
    }

    /** Guest path with the home directory shortened to "~". */
    fun shortPath(path: String): String = when {
        path == Paths.GUEST_HOME -> "~"
        path.startsWith(Paths.GUEST_HOME + "/") -> "~" + path.removePrefix(Paths.GUEST_HOME)
        else -> path
    }
}
