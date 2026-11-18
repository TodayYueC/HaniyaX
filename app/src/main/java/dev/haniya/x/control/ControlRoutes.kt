package dev.haniya.x.control

/**
 * Endpoints of the local control API (apiVersion 1). There is deliberately no endpoint that approves a
 * plugin's commands: approval exists only in the app UI. Pure JVM code (unit tested).
 */
sealed interface ApiRoute {
    data object Version : ApiRoute
    data object Plugins : ApiRoute
    data class Plugin(val id: String) : ApiRoute
    data class Validate(val id: String) : ApiRoute
    data class Preview(val id: String) : ApiRoute
    /** The plugin as a .hxcard zip (local.json excluded). */
    data class Export(val id: String) : ApiRoute
    data object Reload : ApiRoute
    data object Services : ApiRoute
    data class ServiceOp(val name: String, val op: String) : ApiRoute
    data class ServiceLogs(val name: String) : ApiRoute
    data object Projects : ApiRoute
    data object Tasks : ApiRoute
}

/** Either a matched route or the HTTP status to answer (404 unknown path, 405 wrong method, 400 bad id). */
data class RouteMatch(val route: ApiRoute?, val status: Int = 200, val message: String? = null)

object ControlRoutes {
    private val PLUGIN_ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SERVICE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    val SERVICE_OPS = setOf("start", "stop", "restart")

    fun match(method: String, segments: List<String>): RouteMatch {
        if (segments.firstOrNull() != "v1") return notFound()
        val s = segments.drop(1)
        fun m(expected: String, r: () -> ApiRoute): RouteMatch =
            if (method == expected) RouteMatch(r()) else RouteMatch(null, 405, "此地址只接受 $expected 请求")
        return when {
            s == listOf("version") -> m("GET") { ApiRoute.Version }
            s == listOf("plugins") -> m("GET") { ApiRoute.Plugins }
            s == listOf("reload") -> m("POST") { ApiRoute.Reload }
            s == listOf("services") -> m("GET") { ApiRoute.Services }
            s == listOf("projects") -> m("GET") { ApiRoute.Projects }
            s == listOf("tasks") -> m("GET") { ApiRoute.Tasks }
            s.size in 2..3 && s[0] == "plugins" -> {
                val id = s[1]
                if (!PLUGIN_ID.matches(id) || id.length > 64) return RouteMatch(null, 400, "卡片 id 无效：只能使用小写字母、数字和连字符")
                when (s.getOrNull(2)) {
                    null -> m("GET") { ApiRoute.Plugin(id) }
                    "validate" -> m("POST") { ApiRoute.Validate(id) }
                    "preview" -> m("GET") { ApiRoute.Preview(id) }
                    "export" -> m("GET") { ApiRoute.Export(id) }
                    else -> notFound()
                }
            }
            s.size == 3 && s[0] == "services" -> {
                val name = s[1]
                if (!SERVICE_NAME.matches(name)) return RouteMatch(null, 400, "服务名无效")
                when (val op = s[2]) {
                    in SERVICE_OPS -> m("POST") { ApiRoute.ServiceOp(name, op) }
                    "logs" -> m("GET") { ApiRoute.ServiceLogs(name) }
                    else -> notFound()
                }
            }
            else -> notFound()
        }
    }

    private fun notFound() = RouteMatch(null, 404, "没有这个接口，请运行 hx help 查看可用命令")
}
