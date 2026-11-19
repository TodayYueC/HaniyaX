package dev.haniya.x.plugins

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.ToastState
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A validated, extracted .hxcard waiting to be installed. */
internal data class ImportCandidate(
    val id: String,
    val name: String,
    val version: String,
    val trust: PluginTrust,
    val hasParams: Boolean,
    /** Directory holding plugin.json (inside [work]). */
    val src: File,
    /** Scratch directory to delete afterwards. */
    val work: File,
    val exists: Boolean,
    val existingVersion: String?,
)

internal class ImportFailure(val title: String, val errors: List<String>) : Exception(title)

internal object PluginImport {
    private fun importRoot(context: Context) = File(context.cacheDir, "hxcard-import")

    /** Copies, unpacks (zip-slip safe, size-limited) and validates [uri]. Blocking; call on IO. */
    fun prepare(context: Context, uri: Uri): ImportCandidate {
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) throw ImportFailure("工作区尚未就绪", listOf("请先完成工作区的创建，再导入卡片。"))
        val root = importRoot(context)
        root.listFiles()?.forEach { GuestFiles.deleteTree(it) }
        val work = File(root, System.nanoTime().toString())
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportFailure("无法读取文件", listOf("所选文件无法打开。"))
            val src = try {
                input.use { HxCard.extract(it, work) }
            } catch (e: HxCard.ImportException) {
                throw ImportFailure("无法导入这个文件", listOf(e.message ?: "压缩包无效"))
            } catch (e: java.util.zip.ZipException) {
                throw ImportFailure("无法导入这个文件", listOf("这不是有效的 .hxcard 或 .zip 文件"))
            }
            val text = File(src, HxCard.MANIFEST_FILE).readText()
            val first = ManifestParser.parse(text)
            val id = first.rawId
            if (id == null || !ManifestParser.ID.matches(id)) {
                throw ImportFailure("卡片配置有误", first.errors.ifEmpty { listOf("plugin.json 中的 id 无效") })
            }
            val r = ManifestParser.parse(text, id)
            val m = r.manifest ?: throw ImportFailure("卡片配置有误", r.errors)
            val existing = File(Paths.pluginsHost, id)
            val existingVersion = if (existing.exists()) PluginHost.instance.get(id)?.version else null
            return ImportCandidate(m.id, m.name, m.version, m.trust, m.params.isNotEmpty(), src, work, existing.exists(), existingVersion)
        } catch (e: Exception) {
            GuestFiles.deleteTree(work)
            if (e is ImportFailure) throw e
            throw ImportFailure("无法导入这个文件", listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun install(c: ImportCandidate, toast: ToastState) {
        val r = PluginHost.instance.installFrom(c.src, c.id)
        withContext(Dispatchers.IO) { GuestFiles.deleteTree(c.work) }
        r.fold(
            {
                toast.show(if (c.exists) "已更新卡片「${c.name}」" else "已添加卡片「${c.name}」")
                // Params to fill or commands to approve: go straight to the card's page.
                if (c.hasParams || c.trust == PluginTrust.RunsCommands) Nav.go(Route.Plugin(c.id))
            },
            { toast.show("导入失败：${it.message ?: "未知原因"}", icon = "info") },
        )
    }

    fun discard(c: ImportCandidate) {
        App.scope.launch(Dispatchers.IO) { GuestFiles.deleteTree(c.work) }
    }
}

/**
 * Returns a launcher that picks a .hxcard / .zip file, validates it, asks before overwriting an existing
 * card with the same id, installs it into ~/.haniyax/plugins/<id> and, when the card declares params or
 * runs commands, opens its page. Used by the home screen's edit mode.
 */
@Composable
fun rememberPluginImporter(): () -> Unit {
    val context = LocalContext.current
    val toast = LocalToast.current
    var candidate by remember { mutableStateOf<ImportCandidate?>(null) }
    var askOverwrite by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ImportFailure?>(null) }
    var showFailure by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        App.scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { PluginImport.prepare(context.applicationContext, uri) } }
            r.fold(
                { c ->
                    if (c.exists) { candidate = c; askOverwrite = true }
                    else PluginImport.install(c, toast)
                },
                { e ->
                    failure = e as? ImportFailure ?: ImportFailure("无法导入这个文件", listOf(e.message ?: e.javaClass.simpleName))
                    showFailure = true
                },
            )
        }
    }

    OverlaySheet(open = askOverwrite, onDismiss = { askOverwrite = false; candidate?.let { PluginImport.discard(it) } }, title = "替换已有卡片") {
        val c = ReliefTheme.colors
        val cand = candidate
        if (cand != null) {
            Text(
                dev.haniya.relief.i18n.I18n.tr("已有一张 id 为 {0} 的卡片{1}。替换为 v{2} 后，本机参数会保留；", cand.id, cand.existingVersion?.let { " (v$it)" } ?: "", cand.version) +
                    if (cand.trust == PluginTrust.RunsCommands) dev.haniya.relief.i18n.I18n.tr("如果命令有变化，需要重新确认。") else "",
                style = ReliefTheme.type.body, color = c.ink, translate = false,
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("取消", onClick = { askOverwrite = false; PluginImport.discard(cand) }, modifier = Modifier.weight(1f))
                PrimaryButton("替换", onClick = {
                    askOverwrite = false
                    App.scope.launch(Dispatchers.Main.immediate) { PluginImport.install(cand, toast) }
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    OverlaySheet(open = showFailure, onDismiss = { showFailure = false }, title = failure?.title ?: "无法导入") {
        val c = ReliefTheme.colors
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            failure?.errors?.take(12)?.forEach { Text("· $it", style = monoStyle, color = c.ink) }
            val more = (failure?.errors?.size ?: 0) - 12
            if (more > 0) Text("另有 $more 处问题", style = ReliefTheme.type.caption, color = c.ink2)
            Text("文件没有导入，现有卡片保持不变。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            GlassButton("知道了", onClick = { showFailure = false }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }

    return remember(launcher) {
        { launcher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*")) }
    }
}
