package dev.haniya.x.dsh

import androidx.compose.ui.graphics.toArgb
import dev.haniya.relief.theme.ReliefColors
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.services.ServiceDef
import dev.haniya.x.services.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile

/** Versions reported by `dsh.sh versions` (empty strings when unknown). */
@Serializable
data class DshVersions(
    val dsh: String = "",
    val node: String = "",
    val latest: String = "",
    val plugin: String = "",
    val pluginRange: String = "",
) {
    val installed get() = dsh.isNotBlank()
    val updateAvailable get() = installed && latest.isNotBlank() && DshLogic.compare(latest, dsh) > 0
}

sealed interface DshState {
    data object Checking : DshState
    data object NotInstalled : DshState
    data class Installed(val versions: DshVersions) : DshState
    data class Error(val message: String) : DshState
}

/** Pure helpers (unit-tested). */
object DshLogic {
    private val tokenUrl = Regex("""https?://[^\s'"]+[?&]token=([A-Za-z0-9._~%-]+)""")

    /** The launch token printed in the most recent `dsh web` startup lines, or null. */
    fun lastToken(lines: List<String>): String? {
        for (i in lines.indices.reversed()) {
            tokenUrl.find(lines[i])?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Compares dotted numeric versions (pre-release suffixes sort before the release). */
    fun compare(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String> {
            val core = v.trim().removePrefix("v").substringBefore('+')
            val main = core.substringBefore('-')
            val pre = if (core.contains('-')) core.substringAfter('-') else ""
            return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (pa, ra) = parts(a); val (pb, rb) = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            ra == rb -> 0
            ra.isEmpty() -> 1
            rb.isEmpty() -> -1
            else -> ra.compareTo(rb)
        }
    }

    /** CSS custom properties the mobile front end / injected styles read. */
    fun cssVars(c: ReliefColors): Map<String, String> {
        fun hex(color: androidx.compose.ui.graphics.Color): String {
            val argb = color.toArgb()
            val a = (argb ushr 24) and 0xFF; val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
            return if (a == 0xFF) "#%02x%02x%02x".format(r, g, b) else "rgba($r,$g,$b,${"%.3f".format(java.util.Locale.US, a / 255f)})"
        }
        return linkedMapOf(
            "--hx-bg" to hex(c.bg), "--hx-glass" to hex(c.glass), "--hx-ink" to hex(c.ink), "--hx-ink2" to hex(c.ink2),
            "--hx-accent" to hex(c.accent), "--hx-acc2" to hex(c.acc2), "--hx-mid" to hex(c.mid), "--hx-hi" to hex(c.hi),
            "--hx-shadow" to hex(c.shadow), "--hx-track" to hex(c.track), "--hx-soft" to hex(c.soft), "--hx-soft2" to hex(c.soft2),
            "--hx-line" to hex(c.line), "--hx-danger" to hex(c.danger), "--hx-amber" to hex(c.amber), "--hx-on" to hex(c.on),
        )
    }

    /** JavaScript that applies [vars] to <html> (+ data-theme) and announces them with an 'hx-theme' event. */
    fun themeScript(vars: Map<String, String>, dark: Boolean): String {
        val json = vars.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        return """
            (function(){try{
              var v=$json, d=document.documentElement;
              if(!d){return;}
              for(var k in v){d.style.setProperty(k,v[k]);}
              d.setAttribute('data-theme','${if (dark) "dark" else "light"}');
              d.style.colorScheme='${if (dark) "dark" else "light"}';
              window.__HX_THEME__={dark:${dark},vars:v};
              window.dispatchEvent(new CustomEvent('hx-theme',{detail:window.__HX_THEME__}));
            }catch(e){}})();
        """.trimIndent()
    }
}

/**
 * DSH lifecycle: install/update jobs, version checks, the managed `dsh web` service and its launch token.
 */
object DshManager {
    const val SERVICE = "dsh"
    const val PORT = 3080
    const val JOB_KEY = "dsh"
    private const val SCRIPT = "/opt/haniyax/dsh/dsh.sh"

    private val _state = MutableStateFlow<DshState>(DshState.Checking)
    val state: StateFlow<DshState> = _state.asStateFlow()

    val baseUrl = "http://127.0.0.1:$PORT"

    fun refresh() {
        App.scope.launch {
            val r = Runtime.workspace.exec("bash $SCRIPT versions", timeoutMs = 40_000)
            val line = r.stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            _state.value = when {
                line != null -> runCatching { AppJson.decodeFromString(DshVersions.serializer(), line) }
                    .map { if (it.installed) DshState.Installed(it) else DshState.NotInstalled }
                    .getOrElse { DshState.Error("无法读取版本信息") }
                !r.ok && r.stderr.isNotBlank() -> DshState.Error(r.stderr.lines().last { it.isNotBlank() })
                else -> DshState.NotInstalled
            }
        }
    }

    fun install(): String = Runtime.jobs.start("安装 DeepSeek Harness", "bash $SCRIPT install", key = JOB_KEY)
    fun update(): String = Runtime.jobs.start("更新 DeepSeek Harness", "bash $SCRIPT update", key = JOB_KEY)
    fun installPlugin(): String = Runtime.jobs.start("安装移动前端", "bash $SCRIPT plugin", key = JOB_KEY)

    /** Defines the managed `dsh web` service (if missing) and starts it unless it is already up. */
    fun ensureServer() {
        val existing = App.services.services.value.firstOrNull { it.def.name == SERVICE }
        if (existing == null) {
            App.services.define(
                ServiceDef(
                    name = SERVICE,
                    command = "dsh web --no-open --host 127.0.0.1 --port $PORT",
                    cwd = Paths.GUEST_HOME,
                    port = PORT,
                    owner = "dsh",
                    description = "DeepSeek Harness",
                ),
            )
            App.services.reload()
        }
        val st = App.services.services.value.firstOrNull { it.def.name == SERVICE }?.status
        if (st == null || st == ServiceStatus.Stopped || st == ServiceStatus.Crashed) App.services.start(SERVICE)
    }

    fun restartServer() = App.services.restart(SERVICE)

    private fun logFile(): File = Paths.host("${Paths.GUEST_HX}/logs/$SERVICE.log")

    /** Last ~200 lines of the service log (IO). */
    suspend fun logTail(lines: Int = 200): List<String> = withContext(Dispatchers.IO) { tail(logFile(), lines) }

    /** The launch token of the running server, parsed from its startup output. */
    suspend fun token(): String? = DshLogic.lastToken(logTail(400))

    private fun tail(f: File, n: Int): List<String> {
        if (!f.isFile) return emptyList()
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = (len - 256 * 1024).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            return String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(n)
        }
    }
}
