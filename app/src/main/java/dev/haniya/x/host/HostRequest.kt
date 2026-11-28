package dev.haniya.x.host

enum class HostMode(val id: String) { Disabled("disabled"), Normal("normal"), Root("root") }

enum class HostPolicy(val id: String) { Manual("manual"), Routine("routine"), Full("full") }

internal enum class HostRisk { Routine, Extended, Confirm }

internal data class HostCommand(
    val root: Boolean,
    val tty: Boolean,
    val timeoutMs: Long,
    val geometry: Int,
    val nativeArgs: List<String>,
    val description: String,
    val operation: String,
    val risk: HostRisk,
    val expectsInput: Boolean = false,
) {
    fun needsApproval(policy: HostPolicy): Boolean = when (policy) {
        HostPolicy.Manual -> true
        HostPolicy.Routine -> root || risk != HostRisk.Routine
        HostPolicy.Full -> false
    }
}

internal object HostRequest {
    const val MAX_FILE = 1024L * 1024 * 1024
    private val settingKey = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val property = Regex("[A-Za-z0-9_.-]{1,128}")
    private val namespaces = setOf("system", "secure", "global")

    fun command(args: List<String>, root: Boolean, seconds: Int, geometry: Int, sharedStorage: String): HostCommand {
        require(seconds in 0..3600 && args.isNotEmpty()) { "Invalid timeout or missing command" }
        val op = args[0]
        val values = args.drop(1)
        val shell = op == "shell" || op == "root"
        require(geometry == 0 || (shell && values.isEmpty() && (geometry ushr 16) > 0 && (geometry and 65535) > 0))
        val useRoot = root || op == "root" || op == "install"
        val tty = shell && values.isEmpty()
        val timeout = (if (seconds != 0) seconds else when {
            tty || op == "logcat" -> 900
            op in setOf("install", "push", "pull") -> 300
            else -> 120
        }) * 1000L

        fun plan(backend: List<String>, risk: HostRisk, description: String = op, input: Boolean = false) =
            HostCommand(useRoot, tty, timeout, geometry, backend, description, op, risk, input)

        fun exec(argv: List<String>, risk: HostRisk, description: String = op, input: Boolean = false) =
            plan(listOf("exec") + argv, risk, description, input)

        return when (op) {
            "shell", "root" -> {
                require(!root && values.size <= 1) { "Expected shell/root with zero or one script argument" }
                if (tty) {
                    exec(listOf("/system/bin/sh", "-i"), HostRisk.Confirm, "$op (interactive terminal)", input = true)
                } else {
                    val script = values.single()
                    require(script.length <= 4096 && script.isNotBlank()) { "Shell scripts must contain 1–4096 characters" }
                    val exact = literalShell(script)
                    if (exact != null) {
                        exec(exact, HostRisk.Routine, "$op ${visible(script)}")
                    } else {
                        exec(listOf("/system/bin/sh", "-c", script), HostRisk.Confirm, "$op ${visible(script)}", input = true)
                    }
                }
            }
            "id" -> {
                require(values.isEmpty())
                exec(listOf("/system/bin/id"), HostRisk.Routine)
            }
            "getprop" -> {
                require(values.isEmpty() || values.size == 1 && property.matches(values[0])) { "Invalid Android property name" }
                exec(listOf("/system/bin/getprop") + values, HostRisk.Routine, args.joinToString(" "))
            }
            "apps" -> {
                require(values.isEmpty())
                exec(listOf("/system/bin/pm", "list", "packages"), HostRisk.Routine)
            }
            "logcat" -> {
                require(values.isEmpty())
                exec(listOf("/system/bin/logcat"), HostRisk.Extended)
            }
            "push", "pull" -> {
                require(values.size == if (op == "push") 2 else 1)
                val path = transferPath(values[0], sharedStorage)
                val native = if (op == "push") listOf(op, path, fileLength(values[1]).toString()) else listOf(op, path)
                val label = if (op == "push") "push → ${visible(path)} (${native.last()} B, replace)" else "pull ← ${visible(path)}"
                plan(native, if (op == "push") HostRisk.Confirm else HostRisk.Extended, label, input = op == "push")
            }
            "install" -> {
                require(values.size == 2 && values[1].length in 1..255 && '/' !in values[1]) { "Invalid APK file name" }
                val size = fileLength(values[0])
                require(size > 0) { "APK is empty" }
                exec(listOf("/system/bin/pm", "install", "-S", size.toString()), HostRisk.Confirm,
                    "pm install ${visible(values[1])} ($size B)", input = true)
            }
            "settings" -> {
                val action = values.getOrNull(0)
                val count = when (action) {
                    "list" -> 2
                    "get", "delete" -> 3
                    "put" -> 4
                    else -> throw IllegalArgumentException("Invalid settings action")
                }
                require(values.size == count && values[1] in namespaces) { "Invalid settings namespace or arguments" }
                require(count < 3 || settingKey.matches(values[2]) && !values[2].startsWith("-")) { "Invalid settings key" }
                require(count < 4 || values[3].length <= 4096) { "Settings value exceeds 4096 characters" }
                val risk = if (count == 4 || action == "delete") HostRisk.Confirm else HostRisk.Routine
                val shown = args.take(4).joinToString(" ") + if (count == 4) " " + visible(values[3]) else ""
                exec(listOf("/system/bin/settings") + values, risk, shown)
            }
            else -> throw IllegalArgumentException("Unknown Android host command")
        }
    }

    private fun literalShell(script: String): List<String>? = when (script) {
        "id", "id -u" -> listOf("/system/bin/id") + script.split(' ').drop(1)
        "getprop" -> listOf("/system/bin/getprop")
        "pm list packages" -> listOf("/system/bin/pm", "list", "packages")
        "settings list system", "settings list secure", "settings list global" ->
            listOf("/system/bin/settings") + script.split(' ').drop(1)
        else -> null
    }

    private fun fileLength(text: String): Long =
        requireNotNull(text.toLongOrNull()?.takeIf { it in 0..MAX_FILE }) { "Invalid file size (maximum 1 GiB)" }

    private fun transferPath(path: String, sharedStorage: String): String {
        require(path.length <= 1024 && path.startsWith("/") && path.drop(1).split('/').none {
            it.isEmpty() || it == "." || it == ".." || it.any { ch -> ch.isISOControl() }
        }) { "Expected an absolute Android file path with no '.' or '..' components" }
        val mapped = if (path.startsWith("/sdcard/")) sharedStorage + path.removePrefix("/sdcard") else path
        require(mapped.startsWith("$sharedStorage/") || mapped.startsWith("/data/local/tmp/")) {
            "Host file transfers are limited to /sdcard and /data/local/tmp"
        }
        return mapped
    }

    /** Make control characters and bidirectional formatting visible in the on-device approval. */
    private fun visible(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when {
                ch == '"' || ch == '\\' -> append('\\').append(ch)
                ch.isISOControl() || Character.getType(ch) == Character.FORMAT.toInt() ->
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }
}
