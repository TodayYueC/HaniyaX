package dev.haniya.x.control

import android.system.Os
import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.BuildConfig
import dev.haniya.x.core.Paths
import dev.haniya.x.plugins.ApprovalState
import dev.haniya.x.plugins.BlockRenderer
import dev.haniya.x.plugins.HxCard
import dev.haniya.x.plugins.LoadedPlugin
import dev.haniya.x.plugins.PlainPreview
import dev.haniya.x.plugins.PluginHost
import dev.haniya.x.plugins.PluginTrust
import dev.haniya.x.plugins.SourceEvaluator
import dev.haniya.x.plugins.SourceValue
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local control API for the `hx` CLI inside the workspace. proot shares the network namespace, so the guest
 * reaches it at 127.0.0.1:<port>. Bound to loopback only, random port, random 32-byte bearer token written
 * to guest ~/.haniyax/control.json (mode 0600) whenever the workspace is ready. Requests with a non-loopback
 * Host header or any Origin header (browsers) are rejected. There is no endpoint that approves commands.
 */
object ControlServer {
    const val API_VERSION = 1
    private const val TAG = "HxControl"
    private const val MAX_CONCURRENT = 8

    private val started = AtomicBoolean(false)
    private val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val slots = Semaphore(MAX_CONCURRENT)

    @Volatile var port: Int = 0
        private set

    private val controlFile: File get() = File(Paths.hxHost, "control.json")

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val ss = try {
            ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.e(TAG, "control server failed to bind", e)
            started.set(false)
            return
        }
        port = ss.localPort
        thread(name = "hx-control-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { if (ss.isClosed) break else continue }
                App.scope.launch(Dispatchers.IO) { slots.withPermit { serve(s) } }
            }
        }
        App.scope.launch(Dispatchers.IO) { publishLoop() }
    }

    // ------------------------------------------------------------------ control.json

    /** Rewrites control.json on start and whenever the workspace becomes Ready; re-checks it every 30 s. */
    private suspend fun publishLoop() {
        Runtime.workspace.state.collectLatest { st ->
            if (st !is WorkspaceState.Ready) return@collectLatest
            var first = true
            while (true) {
                runCatching { writeControlFile(force = first) }.onFailure { Log.w(TAG, "control.json not written", it) }
                first = false
                delay(30_000)
            }
        }
    }

    private fun controlJson(): String = buildJsonObject {
        put("port", port)
        put("token", token)
        put("apiVersion", API_VERSION)
        put("app", BuildConfig.VERSION_NAME)
        put("url", "http://127.0.0.1:$port")
    }.toString() + "\n"

    private fun writeControlFile(force: Boolean) {
        if (!Paths.host(Paths.GUEST_HOME).isDirectory) return
        val text = controlJson()
        val f = controlFile
        if (!force && f.isFile && runCatching { f.readText() }.getOrNull() == text) return
        val dir = f.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, ".control.json.tmp")
        tmp.delete()
        tmp.createNewFile()
        runCatching { Os.chmod(tmp.path, 0x180) } // 0600 before the token is written
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() } }
        runCatching { Os.chmod(f.path, 0x180) }
    }

    // ------------------------------------------------------------------ connections

    private suspend fun serve(s: Socket) {
        try {
            s.use { sock ->
                if (!sock.inetAddress.isLoopbackAddress) return
                sock.soTimeout = 15_000
                val resp = try {
                    respond(Http.read(sock.getInputStream()))
                } catch (e: HttpException) {
                    error(e.status, e.message ?: "请求无效")
                } catch (_: SocketTimeoutException) {
                    return
                }
                Http.write(sock.getOutputStream(), resp)
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection ended: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
        }
    }

    private fun error(status: Int, message: String) = HttpResponse.json(status, buildJsonObject {
        put("ok", false); put("status", status); put("error", message)
    }.toString())

    private fun ok(e: JsonElement, status: Int = 200) = HttpResponse.json(status, e.toString())

    private suspend fun respond(req: HttpRequest): HttpResponse {
        if (!Http.isAllowedHost(req.header("host"), port)) return error(403, "只接受来自本机 127.0.0.1 的请求")
        if (req.header("origin") != null) return error(403, "不接受浏览器发起的请求")
        if (!Http.isAuthorized(req.header("authorization"), token)) return error(401, "令牌无效：请使用 ~/.haniyax/control.json 中的 token（App 重启后会更换）")
        val match = ControlRoutes.match(req.method, req.segments)
        val route = match.route ?: return error(match.status, match.message ?: "请求无效")
        return try {
            handle(route, req)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "handler failed", e)
            error(500, "处理请求时出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val host: PluginHost get() = PluginHost.instance

    private suspend fun handle(r: ApiRoute, req: HttpRequest): HttpResponse = when (r) {
        ApiRoute.Version -> ok(buildJsonObject {
            put("ok", true)
            put("apiVersion", API_VERSION)
            put("app", BuildConfig.VERSION_NAME)
            put("workspace", workspaceState())
            put("pluginsDir", "${Paths.GUEST_HX}/plugins")
            put("servicesDir", "${Paths.GUEST_HX}/services")
            put("docs", "${Paths.GUEST_SDK}/docs/README.md")
        })
        ApiRoute.Plugins -> {
            host.reloadNow()
            ok(buildJsonObject {
                put("ok", true)
                put("plugins", JsonArray(host.loaded.value.values.sortedBy { it.id }.map { pluginJson(it, detail = false) }))
            })
        }
        is ApiRoute.Plugin -> {
            host.reloadNow()
            val lp = host.get(r.id) ?: return notFoundPlugin(r.id)
            ok(pluginJson(lp, detail = true))
        }
        is ApiRoute.Validate -> {
            val lp = if (req.body.isNotEmpty()) host.validateText(r.id, req.bodyText) else {
                host.reloadNow()
                host.get(r.id) ?: return notFoundPlugin(r.id)
            }
            ok(buildJsonObject {
                put("ok", lp.errors.isEmpty())
                put("id", r.id)
                put("source", if (req.body.isNotEmpty()) "body" else "disk")
                put("trust", trustId(lp))
                put("approval", approvalId(lp.approval))
                put("errors", strings(lp.errors))
                put("warnings", strings(lp.warnings))
                put("missingParams", strings(lp.params.missing))
                put("commands", commandsJson(lp))
                put("note", noteFor(lp))
            })
        }
        is ApiRoute.Preview -> preview(r.id, req)
        is ApiRoute.Export -> {
            host.reloadNow()
            val lp = host.get(r.id) ?: return notFoundPlugin(r.id)
            if (lp.manifest == null) error(422, "卡片有错误，修正后再导出：${lp.errors.firstOrNull() ?: ""}")
            else {
                val bo = java.io.ByteArrayOutputStream()
                HxCard.export(lp.dir, bo)
                HttpResponse(200, "application/zip", bo.toByteArray())
            }
        }
        ApiRoute.Reload -> {
            host.reloadNow()
            App.services.reload()
            ok(buildJsonObject { put("ok", true); put("plugins", host.loaded.value.size) })
        }
        ApiRoute.Services -> ok(buildJsonObject {
            put("ok", true)
            put("services", buildJsonArray {
                App.services.services.value.sortedBy { it.def.name }.forEach { s ->
                    add(buildJsonObject {
                        put("name", s.def.name)
                        put("status", s.status.name.lowercase())
                        put("managed", s.managed)
                        put("pid", s.pid?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("address", s.address?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("port", s.def.port?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("command", s.def.command)
                        put("cwd", s.def.cwd)
                        put("log", s.def.log ?: "${Paths.GUEST_HX}/logs/${s.def.name}.log")
                        put("owner", s.def.owner)
                        put("autostart", s.def.autostart)
                        put("lastExitCode", s.lastExitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("startedAt", s.startedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        })
        is ApiRoute.ServiceOp -> {
            if (App.services.services.value.none { it.def.name == r.name }) {
                App.services.reload()
                delay(300)
            }
            if (App.services.services.value.none { it.def.name == r.name }) error(404, "没有名为 ${r.name} 的服务（hx service list 查看）")
            else {
                when (r.op) {
                    "start" -> App.services.start(r.name)
                    "stop" -> App.services.stop(r.name)
                    else -> App.services.restart(r.name)
                }
                ok(buildJsonObject { put("ok", true); put("name", r.name); put("op", r.op) })
            }
        }
        is ApiRoute.ServiceLogs -> {
            if (App.services.services.value.none { it.def.name == r.name }) error(404, "没有名为 ${r.name} 的服务")
            else {
                val n = req.query["n"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 20
                ok(buildJsonObject { put("ok", true); put("name", r.name); put("lines", strings(App.services.tail(r.name, n))) })
            }
        }
        ApiRoute.Projects -> ok(buildJsonObject {
            put("ok", true)
            put("projects", buildJsonArray {
                App.projects.projects.value.forEach { p ->
                    add(buildJsonObject {
                        put("path", p.path); put("name", p.name); put("pinned", p.pinned)
                        put("lastOpenedAt", p.lastOpenedAt)
                        put("lastAgent", p.lastAgent?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
        })
        ApiRoute.Tasks -> ok(buildJsonObject {
            put("ok", true)
            put("tasks", buildJsonArray {
                Runtime.tasks.tasks.value.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id); put("title", t.title); put("kind", t.kind.name.lowercase())
                        put("status", t.status.name.lowercase())
                        put("agentId", t.agentId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("cwd", t.cwd); put("startedAt", t.startedAt)
                        put("exitCode", t.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("tabOpen", t.tabOpen)
                    })
                }
            })
        })
    }

    private fun notFoundPlugin(id: String) = error(404, "没有 id 为 $id 的卡片（目录 ${Paths.GUEST_HX}/plugins/$id 不存在）")

    private suspend fun preview(id: String, req: HttpRequest): HttpResponse {
        host.reloadNow()
        val lp = host.get(id) ?: return notFoundPlugin(id)
        val m = lp.manifest
        val text = if (m == null) {
            buildString {
                append("┌ ").append(lp.name).append("   [").append(id).append("]\n")
                append("│ 卡片配置有误，首页会显示为错误卡片：\n")
                lp.errors.forEach { append("│ · ").append(it).append('\n') }
                append("└")
            }
        } else {
            val names = m.sources.keys
            val values = withTimeoutOrNull(25_000) { SourceEvaluator.evaluate(lp, names) }
                ?: names.associateWith { SourceValue.failed("读取超时") }
            PlainPreview.render(m, lp.approval, lp.params, values)
        }
        val json = req.query["format"] == "json" || req.header("accept")?.contains("application/json") == true
        return if (json) ok(buildJsonObject {
            put("ok", lp.errors.isEmpty())
            put("id", id)
            put("trust", trustId(lp))
            put("approval", approvalId(lp.approval))
            put("text", text)
            put("errors", strings(lp.errors))
            put("warnings", strings(lp.warnings))
            put("note", noteFor(lp))
        }) else HttpResponse.text(200, text + "\n" + noteFor(lp).let { if (it.isEmpty()) "" else "$it\n" })
    }

    // ------------------------------------------------------------------ json helpers

    private fun strings(l: List<String>) = JsonArray(l.map { JsonPrimitive(it) })

    private fun workspaceState() = when (Runtime.workspace.state.value) {
        is WorkspaceState.Ready -> "ready"
        is WorkspaceState.Installing -> "installing"
        is WorkspaceState.Failed -> "failed"
        WorkspaceState.NotInstalled -> "not-installed"
    }

    private fun trustId(lp: LoadedPlugin) = when (lp.trust) {
        PluginTrust.DisplayOnly -> "display-only"
        PluginTrust.RunsCommands -> "runs-commands"
    }

    private fun approvalId(a: ApprovalState) = when (a) {
        ApprovalState.NotRequired -> "not-required"
        ApprovalState.Approved -> "approved"
        ApprovalState.Pending -> "pending"
        ApprovalState.Changed -> "changed"
    }

    private fun noteFor(lp: LoadedPlugin): String = when {
        lp.errors.isNotEmpty() -> "卡片有 ${lp.errors.size} 处错误，首页会显示为错误卡片。修正 plugin.json 后会自动重新读取。"
        lp.trust == PluginTrust.RunsCommands && lp.approval == ApprovalState.Pending ->
            "这张卡片会运行命令：请告诉用户在 HaniyaX 首页点开这张卡片，查看命令并点「允许运行这些命令」。Agent 无法代为允许。"
        lp.trust == PluginTrust.RunsCommands && lp.approval == ApprovalState.Changed ->
            "命令已变更：请告诉用户在 HaniyaX 中重新确认这张卡片的命令。Agent 无法代为确认。"
        lp.params.missing.isNotEmpty() -> "还缺少参数：${lp.params.missing.joinToString("、")}。用 hx plugin params ${lp.id} 名称=值 填写。"
        else -> ""
    }

    private fun commandsJson(lp: LoadedPlugin) = buildJsonArray {
        lp.commands.forEach { c ->
            add(buildJsonObject {
                put("where", c.where); put("kind", c.kind); put("run", c.run)
                put("cwd", c.cwd?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }

    private fun pluginJson(lp: LoadedPlugin, detail: Boolean): JsonObject = buildJsonObject {
        put("id", lp.id)
        put("name", lp.name)
        put("version", lp.version)
        put("description", lp.manifest?.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("icon", lp.icon)
        put("trust", trustId(lp))
        put("approved", lp.approval == ApprovalState.Approved || lp.approval == ApprovalState.NotRequired)
        put("approval", approvalId(lp.approval))
        put("errors", strings(lp.errors))
        put("warnings", strings(lp.warnings))
        put("missingParams", strings(lp.params.missing))
        put("dir", lp.guestDir)
        if (detail) {
            val m = lp.manifest
            put("params", buildJsonObject {
                m?.params?.values?.forEach { p ->
                    put(p.name, buildJsonObject {
                        put("type", p.type.id)
                        put("label", p.label)
                        put("default", p.default ?: JsonNull)
                        put("value", lp.params.values[p.name] ?: JsonNull)
                        put("local", lp.localValues.containsKey(p.name))
                        put("description", p.description?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("refresh", m?.refreshSec?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sources", buildJsonObject { m?.sources?.values?.forEach { s -> put(s.name, s.type) } })
            put("actions", buildJsonArray {
                m?.actions?.forEach { a ->
                    add(buildJsonObject { put("id", a.id); put("label", a.label); put("type", PlainPreview.actionType(a)) })
                }
            })
            put("tap", m?.tap?.let { JsonPrimitive(it) } ?: JsonNull)
            put("cardSources", strings(m?.let { BlockRenderer.sourcesUsed(it.card.blocks, listOf(it.card.title, it.card.subtitle)).toList() } ?: emptyList()))
            put("commands", commandsJson(lp))
            put("note", noteFor(lp))
        }
    }

    /** For tests / diagnostics only: whether the server is listening. */
    internal fun isRunning(): Boolean = started.get() && port > 0
}
