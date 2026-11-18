package dev.haniya.x.plugins

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Everything the host knows about one plugin directory. Immutable snapshot. */
data class LoadedPlugin(
    val id: String,
    val dir: File,
    /** Guest path of the plugin directory, e.g. /root/.haniyax/plugins/dev-server. */
    val guestDir: String,
    val parse: ParseResult,
    /** Problems with local.json (shown as warnings; the default is used instead). */
    val localProblems: List<String>,
    val localValues: Map<String, JsonElement>,
    val params: EffectiveParams,
    val commands: List<CommandEntry>,
    val hash: String?,
    val approval: ApprovalState,
    /** Changes on every reload that touched this plugin's files; restarts data loops. */
    val stamp: String,
) {
    val manifest: Manifest? get() = parse.manifest
    val errors: List<String> get() = parse.errors
    val warnings: List<String> get() = parse.warnings + localProblems
    val name: String get() = manifest?.name ?: parse.rawName?.takeIf { it.isNotBlank() } ?: id
    val version: String get() = manifest?.version ?: parse.rawVersion ?: "?"
    val icon: String get() = manifest?.icon ?: parse.rawIcon ?: "puzzle"
    /** Invalid plugins are treated conservatively as RunsCommands (nothing runs anyway). */
    val trust: PluginTrust get() = manifest?.trust ?: PluginTrust.RunsCommands
    val runnable: Boolean get() = manifest != null && (trust == PluginTrust.DisplayOnly || approval == ApprovalState.Approved)

    fun templateContext(sources: Map<String, SourceValue> = emptyMap()) =
        TemplateContext(params.values, manifest?.params?.mapValues { it.value.type } ?: emptyMap(), sources)

    fun summary() = PluginSummary(
        id = id, name = name, version = version, description = manifest?.description, icon = icon, trust = trust,
        approved = approval == ApprovalState.Approved || approval == ApprovalState.NotRequired,
        errors = errors,
    )
}

/**
 * Watches ~/.haniyax/plugins (host side) every 2 s, parses and validates manifests, tracks approvals and
 * renders cards. Implementation of [PluginsApi].
 */
class PluginHost : PluginsApi {
    private val approvals = ApprovalStore()
    private val _loaded = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val loaded: StateFlow<Map<String, LoadedPlugin>> = _loaded.asStateFlow()
    private val _plugins = MutableStateFlow<List<PluginSummary>>(emptyList())
    override val plugins: StateFlow<List<PluginSummary>> = _plugins.asStateFlow()
    private val _firstLoadDone = MutableStateFlow(false)
    /** True once the plugin directory has been scanned at least once (distinguishes "loading" from "missing"). */
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    /** When a plugin directory with an approval was first seen missing (approval dropped after [GONE_GRACE_MS]). */
    private val goneSince = HashMap<String, Long>()

    init {
        App.scope.launch(Dispatchers.IO) { watchLoop() }
    }

    fun get(id: String): LoadedPlugin? = _loaded.value[id]

    override fun reload() { trigger.trySend(Unit) }

    @Composable
    override fun Card(id: String, modifier: Modifier) = PluginCard(this, id, modifier)

    override fun approve(id: String) {
        App.scope.launch(Dispatchers.IO) {
            val lp = reloadOne(id) ?: return@launch
            if (lp.manifest != null && lp.trust == PluginTrust.RunsCommands && lp.hash != null) approvals.put(id, lp.hash)
            reloadNow()
        }
    }

    /** Approves only if the command set on disk still hashes to [hash] (what the user was shown). */
    suspend fun approveExact(id: String, hash: String): Boolean = withContext(Dispatchers.IO) {
        val lp = reloadOne(id)
        val ok = lp?.manifest != null && lp.trust == PluginTrust.RunsCommands && lp.hash == hash
        if (ok) approvals.put(id, hash)
        reloadNow()
        ok
    }

    override fun revoke(id: String) {
        App.scope.launch(Dispatchers.IO) { approvals.remove(id); reloadNow() }
    }

    override fun remove(id: String) {
        App.scope.launch(Dispatchers.IO) { removeNow(id) }
    }

    suspend fun removeNow(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!ManifestParser.ID.matches(id)) return@withContext false
        val dir = File(Paths.pluginsHost, id)
        val ok = runCatching { GuestFiles.deleteTree(dir); true }.getOrElse { Log.w(TAG, "remove $id", it); false }
        approvals.remove(id)
        reloadNow()
        ok && !dir.exists()
    }

    /** Writes the user's param values into local.json (other keys in local.json are preserved). */
    suspend fun saveParams(id: String, values: Map<String, JsonPrimitive?>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(Paths.pluginsHost, id)
            require(dir.isDirectory) { "卡片目录不存在" }
            val f = File(dir, HxCard.LOCAL_FILE)
            val old = if (f.exists()) runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() else null
            val oldParams = (old?.get("params") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            for ((k, v) in values) if (v == null) oldParams.remove(k) else oldParams[k] = v
            val out = JsonObject((old ?: JsonObject(emptyMap())).toMutableMap().apply { put("params", JsonObject(oldParams)) })
            val tmp = File(dir, ".local.json.tmp")
            tmp.writeText(prettyJson.encodeToString(JsonElement.serializer(), out) + "\n")
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
            reloadNow()
        }
    }

    /** Validates manifest [text] as if it were plugins/<id>/plugin.json (used by the control API). */
    fun validateText(id: String, text: String): LoadedPlugin {
        val dir = File(Paths.pluginsHost, id)
        return build(id, dir, ManifestParser.parse(text, id), readLocal(dir), stamp = "validate")
    }

    /**
     * Installs an extracted plugin directory [src] as plugins/<id>. Keeps an existing local.json.
     * Returns the installed id.
     */
    suspend fun installFrom(src: File, id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(ManifestParser.ID.matches(id)) { "卡片 id 无效" }
            val root = Paths.pluginsHost.apply { mkdirs() }
            val target = File(root, id)
            val staging = File(root, ".install-$id-${System.nanoTime()}")
            src.copyRecursively(staging, overwrite = true)
            File(staging, HxCard.LOCAL_FILE).delete()
            val keepLocal = File(target, HxCard.LOCAL_FILE).takeIf { it.isFile }?.readBytes()
            if (keepLocal != null) File(staging, HxCard.LOCAL_FILE).writeBytes(keepLocal)
            val old = File(root, ".old-$id-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(old)) throw IllegalStateException("无法替换已有的卡片目录")
            if (!staging.renameTo(target)) {
                old.renameTo(target)
                GuestFiles.deleteTree(staging)
                throw IllegalStateException("无法写入卡片目录")
            }
            GuestFiles.deleteTree(old)
            reloadNow()
            id
        }
    }

    // ---------------------------------------------------------------- loading

    private suspend fun watchLoop() {
        var last: String? = null
        while (true) {
            try {
                val forced = last != null && withTimeoutOrNull(2_000) { trigger.receive(); true } == true
                val sig = signature()
                if (forced || sig != last) {
                    if (!forced && last != null) {
                        delay(400) // debounce: wait until writes settle
                        if (signature() != sig) continue
                    }
                    last = sig
                    reloadNow()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "plugin watch failed", e)
                delay(2_000)
            }
        }
    }

    private fun pluginDirs(): List<File> {
        if (!Paths.host(Paths.GUEST_HOME).isDirectory) return emptyList()
        val root = Paths.pluginsHost
        if (!root.isDirectory) root.mkdirs()
        return root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
    }

    private fun signature(): String = buildString {
        for (d in pluginDirs()) {
            append(d.name)
            for (n in listOf(HxCard.MANIFEST_FILE, HxCard.LOCAL_FILE)) {
                val f = File(d, n)
                append('|').append(f.lastModified()).append(':').append(f.length())
            }
            append('\n')
        }
    }

    suspend fun reloadNow() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dirs = pluginDirs()
            val stored = approvals.all()
            if (Paths.pluginsHost.isDirectory) pruneApprovals(dirs.map { it.name }.toSet(), stored.keys)
            val map = LinkedHashMap<String, LoadedPlugin>()
            for (d in dirs) map[d.name] = load(d, stored[d.name]?.hash)
            _loaded.value = map
            _plugins.value = map.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id })).map { it.summary() }
            _firstLoadDone.value = true
        }
    }

    /**
     * Drops approvals of plugins whose directory has been gone for a while, so a removed card that is later
     * re-created (by `hx plugin remove` + `new`, say) starts unapproved. A short grace period tolerates
     * tools that replace a directory by delete + rename.
     */
    private fun pruneApprovals(present: Set<String>, approved: Set<String>) {
        val now = System.currentTimeMillis()
        goneSince.keys.retainAll(approved - present)
        val drop = ArrayList<String>()
        for (id in approved) {
            if (id in present) continue
            val since = goneSince.getOrPut(id) { now }
            if (now - since >= GONE_GRACE_MS) drop.add(id)
        }
        if (drop.isNotEmpty()) {
            approvals.retainOnly(approved - drop.toSet())
            drop.forEach { goneSince.remove(it) }
        }
    }

    private fun reloadOne(id: String): LoadedPlugin? {
        val d = File(Paths.pluginsHost, id)
        if (!d.isDirectory) return null
        return load(d, approvals.get(id)?.hash)
    }

    private fun load(dir: File, storedHash: String?): LoadedPlugin {
        val mf = File(dir, HxCard.MANIFEST_FILE)
        val parse = when {
            !mf.isFile -> ParseResult(null, listOf("缺少 plugin.json"), emptyList(), dir.name, null, null, null)
            mf.length() > ManifestParser.MAX_MANIFEST_BYTES -> ParseResult(null, listOf("plugin.json 超过 256 KB"), emptyList(), dir.name, null, null, null)
            else -> ManifestParser.parse(runCatching { mf.readText() }.getOrElse { "" }, dir.name)
        }
        val stamp = "${mf.lastModified()}:${mf.length()}:${File(dir, HxCard.LOCAL_FILE).lastModified()}"
        return build(dir.name, dir, parse, readLocal(dir), stamp, storedHash)
    }

    private fun readLocal(dir: File): Pair<Map<String, JsonElement>, String?> {
        val f = File(dir, HxCard.LOCAL_FILE)
        if (!f.isFile) return emptyMap<String, JsonElement>() to null
        return try {
            val e = Json.parseToJsonElement(f.readText())
            if (e !is JsonObject) emptyMap<String, JsonElement>() to "local.json：顶层必须是对象"
            else Trust.localParams(e) to null
        } catch (ex: Exception) {
            emptyMap<String, JsonElement>() to "local.json 不是有效的 JSON，已使用默认参数"
        }
    }

    private fun build(id: String, dir: File, parse: ParseResult, local: Pair<Map<String, JsonElement>, String?>, stamp: String, storedHash: String? = approvals.get(id)?.hash): LoadedPlugin {
        val m = parse.manifest
        val params = if (m != null) Trust.effectiveParams(m, local.first) else EffectiveParams(emptyMap(), emptyList(), emptyList())
        val commands = if (m != null) Trust.commands(m, params.values) else emptyList()
        val trust = m?.trust ?: PluginTrust.RunsCommands
        val hash = if (m != null && trust == PluginTrust.RunsCommands) Trust.hash(id, commands) else null
        val approval = if (m == null) (if (storedHash != null) ApprovalState.Changed else ApprovalState.Pending)
        else ApprovalLogic.state(trust, hash, storedHash)
        return LoadedPlugin(
            id = id, dir = dir, guestDir = "${Paths.GUEST_HX}/plugins/$id", parse = parse,
            localProblems = listOfNotNull(local.second) + params.problems, localValues = local.first,
            params = params, commands = commands, hash = hash, approval = approval, stamp = stamp,
        )
    }

    companion object {
        private const val TAG = "HxPlugins"
        private const val GONE_GRACE_MS = 10_000L
        internal val prettyJson = Json { prettyPrint = true }
        /** The app-wide host (App.plugins is always a PluginHost). */
        val instance: PluginHost get() = App.plugins as PluginHost
    }
}
