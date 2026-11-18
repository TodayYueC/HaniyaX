package dev.haniya.x.plugins

import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Evaluates plugin data sources on Dispatchers.IO. Display-only sources (port/http/file/process) always run;
 * `command` sources run only when the plugin's current command set is approved.
 */
internal object SourceEvaluator {
    private const val MAX_OUTPUT = 64 * 1024
    private const val MAX_HTTP_BODY = 256 * 1024

    suspend fun evaluate(lp: LoadedPlugin, names: Collection<String>): Map<String, SourceValue> = coroutineScope {
        val m = lp.manifest ?: return@coroutineScope emptyMap()
        names.distinct().mapNotNull { n -> m.sources[n] }
            .map { s -> async(Dispatchers.IO) { s.name to evaluateOne(lp, s) } }
            .awaitAll().toMap()
    }

    suspend fun evaluateOne(lp: LoadedPlugin, s: SourceDef): SourceValue = withContext(Dispatchers.IO) {
        if (needsMissingParam(lp, s)) return@withContext SourceValue.MISSING
        val ctx = lp.templateContext()
        try {
            when (s) {
                is SourceDef.Port -> port(Templates.render(s.port, ctx).trim(), s.host)
                is SourceDef.Http -> http(Templates.render(s.url, ctx).trim(), s.expectStatus, s.jsonPath)
                is SourceDef.FileSrc -> file(lp, Templates.render(s.path, ctx).trim(), s.tail, s.maxBytes)
                is SourceDef.Process -> process(Templates.render(s.match, ctx))
                is SourceDef.Command -> {
                    if (lp.approval != ApprovalState.Approved) SourceValue.BLOCKED
                    else command(lp, s)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceValue.failed("读取失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun needsMissingParam(lp: LoadedPlugin, s: SourceDef): Boolean {
        if (lp.params.missing.isEmpty()) return false
        val texts = when (s) {
            is SourceDef.Port -> listOf(s.port)
            is SourceDef.Http -> listOf(s.url)
            is SourceDef.FileSrc -> listOf(s.path)
            is SourceDef.Process -> listOf(s.match)
            is SourceDef.Command -> listOfNotNull(s.run, s.cwd)
        }
        return texts.any { t -> runCatching { Templates.refs(t) }.getOrDefault(emptyList()).any { it.kind == TemplateRef.Kind.Param && it.name in lp.params.missing } }
    }

    private fun port(portText: String, host: String): SourceValue {
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return SourceValue.failed("端口无效：$portText")
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 800) }
            SourceValue(SourceValue.State.Ready, ok = true, value = "open")
        } catch (_: ConnectException) {
            SourceValue(SourceValue.State.Ready, ok = false, value = "closed")
        } catch (_: SocketTimeoutException) {
            SourceValue(SourceValue.State.Ready, ok = false, value = "closed")
        } catch (e: Exception) {
            SourceValue(SourceValue.State.Ready, ok = false, value = "closed", error = "无法连接 $host:$port")
        }
    }

    private fun http(url: String, expect: Int?, jsonPath: String?): SourceValue {
        if (!ManifestParser.isLoopbackRendered(url)) return SourceValue.failed("只能访问本机地址：$url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 3000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "HaniyaX-card")
        }
        return try {
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readCapped(it, MAX_HTTP_BODY) } ?: ""
            val json = parseJsonOrNull(body)
            val ok = expect?.let { status == it } ?: (status in 200..399)
            val value = if (jsonPath != null) JsonPath.text(JsonPath.get(json, jsonPath)) else body.trim()
            SourceValue(
                SourceValue.State.Ready, ok = ok, value = value, stdout = body, lines = SourceValue.splitLines(body),
                json = json, status = status, error = if (!ok) "HTTP $status" else null,
            )
        } catch (_: ConnectException) {
            SourceValue(SourceValue.State.Ready, ok = false, error = "无法连接")
        } catch (_: SocketTimeoutException) {
            SourceValue(SourceValue.State.Ready, ok = false, error = "响应超时")
        } finally {
            conn.disconnect()
        }
    }

    private fun file(lp: LoadedPlugin, raw: String, tail: Int?, maxBytes: Int): SourceValue {
        if (raw.isEmpty()) return SourceValue.failed("没有设置文件路径")
        val expanded = ParamValues.expandHome(raw)
        val guest = if (expanded.startsWith("/")) expanded else "${lp.guestDir}/$expanded"
        val f = GuestFiles.resolve(Paths.rootfs, guest) ?: return SourceValue.failed("路径不在工作区内：$guest")
        if (!f.exists()) return SourceValue.failed("文件不存在：$guest")
        if (f.isDirectory) return SourceValue.failed("这是一个目录：$guest")
        if (!f.canRead()) return SourceValue.failed("没有读取权限：$guest")
        val text = GuestFiles.readTail(f, maxBytes)
        val lines = SourceValue.splitLines(text).let { if (tail != null) it.takeLast(tail) else it }
        val value = if (tail != null) lines.joinToString("\n") else text.trimEnd()
        return SourceValue(SourceValue.State.Ready, ok = true, value = value, stdout = text, lines = lines, json = parseJsonOrNull(text))
    }

    private suspend fun process(match: String): SourceValue {
        if (match.isBlank()) return SourceValue.failed("没有设置进程匹配规则")
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) return SourceValue.failed("工作区尚未就绪")
        // The pattern travels in an env var so the shell running pgrep never matches itself.
        val r = Runtime.workspace.exec("pgrep -af -- \"\$HX_MATCH\"", timeoutMs = 5_000, extraEnv = mapOf("HX_MATCH" to match))
        return when (r.code) {
            0 -> {
                val lines = SourceValue.splitLines(r.stdout).filter { it.isNotBlank() }
                SourceValue(SourceValue.State.Ready, ok = lines.isNotEmpty(), value = lines.size.toString(), stdout = r.stdout, lines = lines)
            }
            1 -> SourceValue(SourceValue.State.Ready, ok = false, value = "0")
            else -> SourceValue.failed("无法检查进程：${r.stderr.trim().lineSequence().lastOrNull()?.take(120) ?: "pgrep 返回 ${r.code}"}")
        }
    }

    private suspend fun command(lp: LoadedPlugin, s: SourceDef.Command): SourceValue {
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) return SourceValue.failed("工作区尚未就绪")
        val entry = lp.commands.firstOrNull { it.where == "sources.${s.name}" } ?: return SourceValue.BLOCKED
        val r = Runtime.workspace.exec(entry.run, cwd = entry.cwd ?: Paths.GUEST_HOME, timeoutMs = s.timeoutSec * 1000L)
        val out = r.stdout.take(MAX_OUTPUT)
        val err = r.stderr.take(MAX_OUTPUT)
        val ok = r.code == 0
        val failNote = if (!ok) "退出码 ${r.code}" + (err.trim().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }?.let { "：${it.take(120)}" } ?: "") else null
        return when (s.parse) {
            ParseMode.TEXT -> SourceValue(SourceValue.State.Ready, ok = ok, value = out.trim(), stdout = out, stderr = err, lines = SourceValue.splitLines(out), error = failNote)
            ParseMode.LINES -> {
                val lines = SourceValue.splitLines(out).filter { it.isNotBlank() }
                SourceValue(SourceValue.State.Ready, ok = ok, value = out.trim(), stdout = out, stderr = err, lines = lines, error = failNote)
            }
            ParseMode.JSON -> {
                val json = parseJsonOrNull(out)
                SourceValue(
                    SourceValue.State.Ready, ok = ok && json != null, value = out.trim(), stdout = out, stderr = err,
                    lines = SourceValue.splitLines(out), json = json, error = failNote ?: if (json == null) "输出不是有效的 JSON" else null,
                )
            }
        }
    }

    fun parseJsonOrNull(s: String): JsonElement? {
        val t = s.trim()
        if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
        return runCatching { Json.parseToJsonElement(t) }.getOrNull()
    }

    private fun readCapped(input: InputStream, max: Int): String {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (bo.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - bo.size()))
            if (n < 0) break
            bo.write(buf, 0, n)
        }
        return bo.toString(Charsets.UTF_8.name())
    }
}
