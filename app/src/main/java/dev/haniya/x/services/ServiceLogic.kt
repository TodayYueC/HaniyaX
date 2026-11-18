package dev.haniya.x.services

import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile

/** Service definition rules (pure, unit-tested). */
object ServiceDefs {
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val NO_EXEC = setOf(
        "cd", "export", "source", ".", "set", "unset", "ulimit", "umask", "alias", "eval", "exec",
        "if", "for", "while", "until", "case", "function", "{", "!", "time", "[", "[[", "test", "trap",
    )

    fun nameError(name: String): String? =
        if (NAME.matches(name)) null else "服务名只能包含字母、数字、点、横线和下划线，最多 64 个字符"

    fun validate(def: ServiceDef): String? {
        nameError(def.name)?.let { return it }
        val port = def.port
        val health = def.healthPath
        val log = def.log
        return when {
            def.command.isBlank() -> "命令不能为空"
            port != null && port !in 1..65535 -> "端口 $port 不在 1–65535 之间"
            !def.cwd.startsWith("/") -> "目录必须是绝对路径"
            health != null && !health.startsWith("/") -> "健康检查路径应以 / 开头"
            log != null && !log.startsWith("/") -> "日志路径必须是绝对路径"
            else -> null
        }
    }

    /**
     * Decodes `<stem>.json`. The file name is authoritative for the service name, so `"name"` may be
     * omitted. Failures carry a Chinese message.
     */
    fun parse(stem: String, text: String): Result<ServiceDef> = runCatching {
        val element = try {
            AppJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("不是有效的 JSON：" + firstLine(e.message))
        }
        val obj = element as? JsonObject ?: throw IllegalArgumentException("应为 JSON 对象")
        val withName = if ("name" in obj) obj else JsonObject(obj + ("name" to JsonPrimitive(stem)))
        val def = try {
            AppJson.decodeFromJsonElement(ServiceDef.serializer(), withName)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("字段格式不正确：" + firstLine(e.message))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("字段格式不正确：" + firstLine(e.message))
        }
        val named = if (def.name == stem) def else def.copy(name = stem)
        validate(named)?.let { throw IllegalArgumentException(it) }
        named
    }

    private fun firstLine(s: String?): String = s?.lineSequence()?.firstOrNull()?.take(160) ?: "未知错误"

    /** Short state word for badges. */
    fun shortLabel(status: ServiceStatus): String = when (status) {
        ServiceStatus.Stopped -> "已停止"
        ServiceStatus.Starting -> "启动中"
        ServiceStatus.Running -> "运行中"
        ServiceStatus.Unhealthy -> "没有响应"
        ServiceStatus.Crashed -> "已退出"
    }

    /** True while a process we started is (still) alive. */
    fun isManagedAlive(s: ServiceState): Boolean =
        s.managed && (s.status == ServiceStatus.Starting || s.status == ServiceStatus.Running || s.status == ServiceStatus.Unhealthy)

    fun encode(def: ServiceDef): String = AppJson.encodeToString(ServiceDef.serializer(), def)

    /** Guest log path: the declared one or ~/.haniyax/logs/<name>.log. */
    fun logPath(def: ServiceDef): String = def.log ?: "${Paths.GUEST_HX}/logs/${def.name}.log"

    fun address(def: ServiceDef): String? = def.port?.let { "127.0.0.1:$it" }

    /**
     * The script handed to `bash -lc`. A simple command gets `exec` so bash is replaced by the server
     * (signals reach it directly); compound commands, assignments and builtins are left untouched.
     */
    fun launchScript(command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return cmd
        if (cmd.any { it == '\n' || it == ';' || it == '&' || it == '|' || it == '(' || it == ')' || it == '`' }) return cmd
        val first = cmd.split(' ', '\t').first()
        if ('=' in first || first.startsWith("$") || first in NO_EXEC) return cmd
        return "exec $cmd"
    }

    fun statusLabel(status: ServiceStatus, managed: Boolean, lastExitCode: Int?): String = when (status) {
        ServiceStatus.Stopped -> "已停止"
        ServiceStatus.Starting -> "启动中"
        ServiceStatus.Running -> if (managed) "运行中" else "运行中 · 由其他程序启动"
        ServiceStatus.Unhealthy -> "没有响应"
        ServiceStatus.Crashed -> if (lastExitCode != null) "已退出 · 退出码 $lastExitCode" else "已退出"
    }
}

/** Reads the last lines of a (possibly large) log file from its end. */
object LogTail {
    fun read(file: File, lines: Int, maxBytes: Long = 1L shl 20): List<String> {
        if (lines <= 0 || !file.isFile) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len == 0L) return emptyList()
            raf.seek(len - 1)
            val end = if (raf.read() == '\n'.code) len - 1 else len
            if (end == 0L) return listOf("")
            val floor = maxOf(0L, end - maxBytes)
            val buf = ByteArray(8192)
            var pos = end
            var newlines = 0
            var start = -1L
            while (pos > floor && start < 0) {
                val n = minOf(buf.size.toLong(), pos - floor).toInt()
                pos -= n
                raf.seek(pos)
                raf.readFully(buf, 0, n)
                for (i in n - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        newlines++
                        if (newlines == lines) { start = pos + i + 1; break }
                    }
                }
            }
            val partialFirst = start < 0 && floor > 0
            if (start < 0) start = floor
            val bytes = ByteArray((end - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            var out = String(bytes, Charsets.UTF_8).split('\n')
            if (partialFirst && out.size > 1) out = out.drop(1)
            return out.map { it.removeSuffix("\r") }.takeLast(lines)
        }
    }
}

/** Makes raw program output readable as plain text: carriage-return redraws and ANSI escapes removed. */
object LogText {
    private val OSC = Regex("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\|$)")
    private val CSI = Regex("\\x1B\\[[0-?]*[ -/]*[@-~]")
    private val CHARSET = Regex("\\x1B[()*+][0-9A-Za-z]")
    private val ESC = Regex("\\x1B.?")
    private val CTRL = Regex("[\\x00-\\x08\\x0B-\\x1F\\x7F]")

    fun clean(line: String): String {
        var s = line.removeSuffix("\r")
        val cr = s.lastIndexOf('\r')
        if (cr >= 0) s = s.substring(cr + 1)
        s = OSC.replace(s, "")
        s = CSI.replace(s, "")
        s = CHARSET.replace(s, "")
        s = ESC.replace(s, "")
        return CTRL.replace(s, "")
    }
}
