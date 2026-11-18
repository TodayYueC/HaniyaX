package dev.haniya.x.plugins

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.ToastState
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.ExecResult
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the card's overlay sheet currently shows. */
internal sealed interface CardSheet {
    data class Blocks(val action: ActionDef.Sheet) : CardSheet
    data class Confirm(val action: ActionDef.Command, val entry: CommandEntry) : CardSheet
    data class Output(val label: String, val entry: CommandEntry, val running: Boolean, val result: ExecResult? = null, val error: String? = null) : CardSheet
    data class Web(val label: String, val url: String) : CardSheet
    data class NeedsApproval(val label: String, val changed: Boolean) : CardSheet
}

@Composable
internal fun rememberActionRunner(host: PluginHost, id: String): ActionRunner {
    val toast = LocalToast.current
    val context = LocalContext.current
    return remember(host, id) { ActionRunner(host, id, toast, context.applicationContext) }
}

/**
 * Runs card actions. Everything that executes something re-reads the plugin first and refuses unless its
 * current command set is approved; the command text that runs is always the one from the approved set.
 */
@Stable
internal class ActionRunner(
    private val host: PluginHost,
    private val id: String,
    private val toast: ToastState,
    private val context: Context,
) {
    var open by mutableStateOf(false)
        private set
    var sheet by mutableStateOf<CardSheet?>(null)
        private set

    private fun show(s: CardSheet) { sheet = s; open = true }
    fun dismiss() { open = false }

    fun run(a: ActionDef) {
        val lp = host.get(id) ?: return
        if (lp.manifest == null) { Nav.go(Route.Plugin(id)); return }
        when (a) {
            is ActionDef.Sheet -> show(CardSheet.Blocks(a))
            is ActionDef.OpenUrl -> {
                val url = Templates.render(a.url, lp.templateContext()).trim()
                if (!ManifestParser.isLoopbackRendered(url)) toast.show("只能打开本机地址", icon = "info")
                else show(CardSheet.Web(a.label, url))
            }
            is ActionDef.Command, is ActionDef.Terminal, is ActionDef.Service -> runGuarded(lp, a)
        }
    }

    private fun runGuarded(lp: LoadedPlugin, a: ActionDef) {
        if (lp.approval != ApprovalState.Approved) {
            show(CardSheet.NeedsApproval(a.label, lp.approval == ApprovalState.Changed)); return
        }
        if (lp.params.missing.isNotEmpty()) {
            toast.show("请先填写卡片参数", icon = "info"); Nav.go(Route.Plugin(id)); return
        }
        if (Runtime.workspace.state.value !is WorkspaceState.Ready) { toast.show("工作区尚未就绪", icon = "info"); return }
        val entry = lp.commands.firstOrNull { it.where == "actions.${a.id}" } ?: return
        when (a) {
            is ActionDef.Command -> if (a.confirm) show(CardSheet.Confirm(a, entry)) else execute(a, entry)
            is ActionDef.Terminal -> {
                runCatching {
                    val tid = Runtime.tasks.start(TaskSpec(TaskKind.Shell, title = a.label, cwd = entry.cwd ?: Paths.GUEST_HOME, command = entry.run))
                    Nav.go(Route.Terminal(tid))
                }.onFailure { toast.show("无法打开终端：${it.message ?: "未知原因"}", icon = "info") }
            }
            is ActionDef.Service -> {
                val known = App.services.services.value.any { it.def.name == a.name }
                if (!known) { toast.show("没有名为 ${a.name} 的服务", icon = "info"); return }
                when (a.op) {
                    ServiceOp.START -> { App.services.start(a.name); toast.show("正在启动 ${a.name}") }
                    ServiceOp.STOP -> { App.services.stop(a.name); toast.show("正在停止 ${a.name}") }
                    ServiceOp.RESTART -> { App.services.restart(a.name); toast.show("正在重启 ${a.name}") }
                }
            }
            else -> Unit
        }
    }

    /** Runs [a] after confirmation; re-checks that the approved command text is still [shown]. */
    fun execute(a: ActionDef.Command, shown: CommandEntry) {
        val lp = host.get(id)
        val entry = lp?.commands?.firstOrNull { it.where == "actions.${a.id}" }
        if (lp == null || lp.approval != ApprovalState.Approved || entry != shown) {
            toast.show("命令已变更，需要重新确认", icon = "info"); dismiss(); return
        }
        if (a.showOutput) show(CardSheet.Output(a.label, entry, running = true)) else dismiss()
        App.scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching { Runtime.workspace.exec(entry.run, cwd = entry.cwd ?: Paths.GUEST_HOME, timeoutMs = a.timeoutSec * 1000L) }
            }
            if (a.showOutput) {
                val cur = sheet
                if (cur is CardSheet.Output && cur.entry == entry && cur.running) {
                    sheet = cur.copy(running = false, result = r.getOrNull(), error = r.exceptionOrNull()?.let { "无法运行：${it.message ?: it.javaClass.simpleName}" })
                }
            } else {
                r.fold(
                    { res -> toast.show(if (res.ok) "${a.label}：已完成" else "${a.label}：结束（退出码 ${res.code}）", icon = if (res.ok) "check" else "info") },
                    { e -> toast.show("无法运行：${e.message ?: e.javaClass.simpleName}", icon = "info") },
                )
            }
        }
    }

    @Composable
    fun Sheets(lp: LoadedPlugin, m: Manifest) {
        val s = sheet
        val title = when (s) {
            is CardSheet.Blocks -> s.action.title?.let { Templates.render(it, lp.templateContext()) }?.takeIf { it.isNotBlank() } ?: s.action.label
            is CardSheet.Confirm -> s.action.label
            is CardSheet.Output -> s.label
            is CardSheet.Web -> s.label
            is CardSheet.NeedsApproval -> s.label
            null -> null
        }
        OverlaySheet(open = open && s != null, onDismiss = { dismiss() }, title = title) {
            when (s) {
                is CardSheet.Blocks -> SheetBlocks(lp, m, s.action)
                is CardSheet.Confirm -> ConfirmContent(s)
                is CardSheet.Output -> OutputContent(s)
                is CardSheet.Web -> WebContent(s)
                is CardSheet.NeedsApproval -> ApprovalContent(s)
                null -> Unit
            }
        }
    }

    @Composable
    private fun SheetBlocks(lp: LoadedPlugin, m: Manifest, a: ActionDef.Sheet) {
        val names = remember(a) { BlockRenderer.sourcesUsed(a.blocks, listOf(a.title)) }
        val values = rememberSourceValues(lp, names, m.refreshSec)
        val rendered = BlockRenderer.render(a.blocks, lp.templateContext(values))
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RenderedBlocks(rendered)
            if (rendered.isEmpty()) Text("这个面板没有内容", style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2)
        }
    }

    @Composable
    private fun ConfirmContent(s: CardSheet.Confirm) {
        val c = ReliefTheme.colors
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将在工作区中运行以下命令：", style = ReliefTheme.type.body, color = c.ink)
            CommandText(s.entry)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("取消", onClick = { dismiss() }, modifier = Modifier.weight(1f))
                PrimaryButton("运行", onClick = { execute(s.action, s.entry) }, modifier = Modifier.weight(1f), icon = "play")
            }
        }
    }

    @Composable
    private fun OutputContent(s: CardSheet.Output) {
        val c = ReliefTheme.colors
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CommandText(s.entry)
            val status = when {
                s.running -> "正在运行…"
                s.error != null -> s.error
                s.result?.ok == true -> "已完成"
                s.result != null -> "已结束，退出码 ${s.result.code}"
                else -> ""
            }
            Text(status, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
            val r = s.result
            if (r != null) {
                val out = SourceValue.splitLines(r.stdout).takeLast(400)
                val err = SourceValue.splitLines(r.stderr).takeLast(200)
                if (out.isNotEmpty()) LogBlock(out, null)
                if (err.isNotEmpty()) LogBlock(err, "错误输出")
                if (out.isEmpty() && err.isEmpty()) Text("没有输出", style = ReliefTheme.type.caption, color = c.ink2)
            }
            GlassButton("关闭", onClick = { dismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }

    @Composable
    private fun WebContent(s: CardSheet.Web) {
        val c = ReliefTheme.colors
        val h = LocalConfiguration.current.screenHeightDp.dp * 0.62f
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.url, style = monoStyle, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            Box(Modifier.fillMaxWidth().height(h).insetSurface(RoundedCornerShape(16.dp), depth = 3.dp).padding(4.dp).clip(RoundedCornerShape(13.dp))) {
                LocalWebView(s.url, Modifier.fillMaxWidth().height(h))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("复制地址", onClick = {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("url", s.url))
                    }
                    toast.show("已复制地址")
                }, modifier = Modifier.weight(1f), icon = "copy", size = ButtonSize.Small)
                GlassButton("关闭", onClick = { dismiss() }, modifier = Modifier.weight(1f), size = ButtonSize.Small)
            }
        }
    }

    @Composable
    private fun ApprovalContent(s: CardSheet.NeedsApproval) {
        val c = ReliefTheme.colors
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (s.changed) "这张卡片的命令已变更，需要你重新确认后才能运行。" else "这张卡片会在工作区中运行命令，需要你先查看并允许。",
                style = ReliefTheme.type.body, color = c.ink,
            )
            Text("确认只能在 HaniyaX 中完成，Agent 无法代你允许。", style = ReliefTheme.type.caption, color = c.ink2)
            Spacer(Modifier.height(2.dp))
            PrimaryButton("查看命令", onClick = { dismiss(); Nav.go(Route.Plugin(id)) }, modifier = Modifier.fillMaxWidth(), icon = "shield")
        }
    }
}

/** Command text in an inset monospace block, with its working directory. */
@Composable
internal fun CommandText(e: CommandEntry, modifier: Modifier = Modifier) {
    val c = ReliefTheme.colors
    Column(
        modifier.fillMaxWidth().insetSurface(RoundedCornerShape(16.dp), depth = 3.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(e.run, style = monoStyle, color = c.ink, translate = false)
        if (e.cwd != null) Text("目录：${e.cwd}", style = ReliefTheme.type.caption, color = c.ink2, translate = false)
    }
}

/** In-app WebView limited to loopback addresses; other links are not followed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LocalWebView(url: String, modifier: Modifier = Modifier) {
    val bg = ReliefTheme.colors.bg
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.argb((bg.alpha * 255).toInt(), (bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt()))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !ManifestParser.isLoopbackRendered(request.url.toString())
                }
                loadUrl(url)
            }
        },
        update = { wv -> if (wv.url == null) wv.loadUrl(url) },
        onRelease = { wv -> wv.stopLoading(); wv.destroy() },
    )
}
