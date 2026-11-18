package dev.haniya.x.dsh

import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.ProgressBar
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.runtime.JobSnapshot
import dev.haniya.x.runtime.JobState
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.services.ServiceStatus
import dev.haniya.x.terminal.findActivity
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route
import dev.haniya.x.ui.Space
import kotlinx.coroutines.delay

/** DeepSeek Harness inside HaniyaX: install on demand, auto-start/connect the server, show it in-app. */
@Composable
fun DshScreen() {
    val state by DshManager.state.collectAsState()
    val jobs by Runtime.jobs.jobs.collectAsState()
    val job = jobs.firstOrNull { it.title.contains("DeepSeek") || it.title.contains("移动前端") }
        ?.takeIf { Runtime.jobs.runningForKey(DshManager.JOB_KEY)?.id == it.id || it.state != JobState.Succeeded }
    var lastJobState by remember { mutableStateOf<JobState?>(null) }

    LaunchedEffect(Unit) { DshManager.refresh() }
    LaunchedEffect(job?.state) {
        if (lastJobState == JobState.Running && job?.state == JobState.Succeeded) DshManager.refresh()
        lastJobState = job?.state
    }

    when (val s = state) {
        DshState.Checking -> Page("DeepSeek Harness") { LoadingCard("正在检查 DeepSeek Harness…", emptyList()) }
        is DshState.Error -> Page("DeepSeek Harness") {
            GlassCard {
                Text("无法读取 DeepSeek Harness 状态", style = ReliefTheme.type.h3)
                Text(s.message, style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2, translate = false)
                Spacer(Modifier.height(12.dp))
                GlassButton("重试", onClick = { DshManager.refresh() }, icon = "refresh")
            }
        }
        DshState.NotInstalled -> InstallPage(job)
        is DshState.Installed -> if (job?.state == JobState.Running) Page("DeepSeek Harness") { JobCard(job) } else WorkPage(s.versions)
    }
}

@Composable
private fun InstallPage(job: JobSnapshot?) {
    val c = ReliefTheme.colors
    Page("DeepSeek Harness", caption = "DeepSeek 的开源智能体工作台") {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile("spark", size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text("在手机上使用 DeepSeek Harness", style = ReliefTheme.type.h3)
                    Text("会话、工具调用、审批与文件变更都在 HaniyaX 内完成，无需浏览器。", style = ReliefTheme.type.caption, color = c.ink2)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("将安装 Node.js 24 与 DeepSeek Harness（约 200 MB），安装在工作区内，与终端和智能体共用同一个 Linux 环境。", style = ReliefTheme.type.body, color = c.ink2)
            Spacer(Modifier.height(14.dp))
            if (job == null || job.state != JobState.Running) PrimaryButton("安装", onClick = { DshManager.install() }, icon = "download")
        }
        if (job != null) JobCard(job)
    }
}

@Composable
private fun JobCard(job: JobSnapshot) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    GlassCard {
        Text(job.title, style = ReliefTheme.type.h3)
        Spacer(Modifier.height(10.dp))
        for (s in job.steps) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                HIcon(if (s.failed) "warn" else if (s.done) "check" else "clock", size = 16.dp, tint = if (s.failed) c.amberInk else if (s.done) c.mid else c.ink2)
                Text(s.label, style = ReliefTheme.type.label, color = if (s.done) c.ink else c.ink2)
            }
        }
        if (job.state == JobState.Running) {
            Spacer(Modifier.height(8.dp))
            ProgressBar(job.progress ?: 0.05f, Modifier.fillMaxWidth())
        }
        if (job.error != null) {
            Spacer(Modifier.height(10.dp))
            Text(job.error, style = ReliefTheme.type.body, color = c.amberInk, translate = false)
        }
        Spacer(Modifier.height(10.dp))
        LogTail(job.log.takeLast(6))
        if (job.state == JobState.Failed || job.state == JobState.Cancelled) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("重试", onClick = { DshManager.install() }, size = ButtonSize.Small, icon = "refresh")
                GlassButton("复制日志", size = ButtonSize.Small, icon = "copy", onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("dsh", job.log.joinToString("\n")))
                })
            }
        }
    }
}

@Composable
private fun LogTail(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(12.dp)) {
        for (l in lines) Text(l, style = ReliefTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = ReliefTheme.colors.ink2, maxLines = 2, translate = false)
    }
}

@Composable
private fun LoadingCard(text: String, log: List<String>) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("spark", size = 40.dp)
            Text(text, style = ReliefTheme.type.h3, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(0.35f, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        LogTail(log.takeLast(6))
    }
}

private sealed interface Conn {
    data object Starting : Conn
    data class Failed(val reason: String) : Conn
    data class Ready(val token: String) : Conn
}

@Composable
private fun WorkPage(versions: DshVersions) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val services by App.services.services.collectAsState()
    val svc = services.firstOrNull { it.def.name == DshManager.SERVICE }
    var conn by remember { mutableStateOf<Conn>(Conn.Starting) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var sandboxOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    // Theme follows the app palette.
    val script = DshLogic.themeScript(DshLogic.cssVars(c), c.isDark)
    LaunchedEffect(script) { DshWebView.setTheme(script) }

    // Start or connect, then wait for the token.
    LaunchedEffect(Unit) {
        DshManager.ensureServer()
        var waited = 0
        while (true) {
            val st = App.services.services.value.firstOrNull { it.def.name == DshManager.SERVICE }
            log = DshManager.logTail(40)
            if (st?.status == ServiceStatus.Running) {
                val t = DshManager.token()
                if (t != null) { conn = Conn.Ready(t); break }
                if (st.managed.not() && waited > 8_000) {
                    conn = Conn.Failed("端口 ${DshManager.PORT} 上已有一个不由 HaniyaX 管理的 DeepSeek Harness，无法取得它的登录令牌。可以在菜单中「重启服务」改由 HaniyaX 启动。")
                    break
                }
            }
            if (st?.status == ServiceStatus.Crashed) {
                conn = Conn.Failed("DeepSeek Harness 启动失败（退出码 ${st.lastExitCode ?: "未知"}）。日志的最后几行见下方。")
                break
            }
            if (waited > 90_000) { conn = Conn.Failed("等待 DeepSeek Harness 启动超时。"); break }
            delay(1_000); waited += 1_000
        }
    }

    // File chooser bridge for uploads/attachments inside DSH.
    var pendingCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pendingCallback?.onReceiveValue(uris.toTypedArray()); pendingCallback = null
    }
    DisposableEffect(Unit) {
        DshWebView.onFileChooser = { cb, accept ->
            pendingCallback?.onReceiveValue(null)
            pendingCallback = cb
            picker.launch(accept.firstOrNull { it.isNotBlank() } ?: "*/*")
        }
        DshWebView.onPageState = { l, _ -> loading = l; canGoBack = DshWebView.current()?.canGoBack() == true }
        onDispose {
            DshWebView.onFileChooser = null
            DshWebView.onPageState = null
            DshWebView.release()
        }
    }

    BackHandler {
        val w = DshWebView.current()
        if (w != null && w.canGoBack() && conn is Conn.Ready) w.goBack() else Nav.back()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Space.gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconCircleButton("chevL", contentDescription = "返回", onClick = { Nav.back() }, size = 40.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text("DeepSeek Harness", style = ReliefTheme.type.title)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val (dot, label) = when (val k = conn) {
                            is Conn.Ready -> c.accent to (if (loading) "加载中" else "已连接 · ${versions.dsh}")
                            Conn.Starting -> c.ink2 to "正在启动"
                            is Conn.Failed -> c.amber to "未连接"
                        }
                        Box(Modifier.size(7.dp).background(dot, CircleShape))
                        Text(label, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
                    }
                }
                IconCircleButton("sliders", contentDescription = "DeepSeek Harness 菜单", onClick = { menuOpen = true }, size = 40.dp, iconSize = 18.dp)
            }
            when (val k = conn) {
                Conn.Starting -> Column(Modifier.padding(horizontal = Space.gutter)) { LoadingCard("正在启动 DeepSeek Harness…", log) }
                is Conn.Failed -> Column(Modifier.padding(horizontal = Space.gutter).verticalScroll(rememberScrollState())) {
                    GlassCard {
                        Text("暂时无法连接", style = ReliefTheme.type.h3)
                        Spacer(Modifier.height(6.dp))
                        Text(k.reason, style = ReliefTheme.type.body, color = c.ink2)
                        Spacer(Modifier.height(12.dp))
                        LogTail(log.takeLast(8))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("重启服务", onClick = { DshManager.restartServer(); conn = Conn.Starting; Nav.back(); Nav.go(Route.Dsh) }, size = ButtonSize.Small, icon = "refresh")
                            GlassButton("服务日志", onClick = { Nav.go(Route.Service(DshManager.SERVICE)) }, size = ButtonSize.Small)
                        }
                    }
                }
                is Conn.Ready -> if (activity != null) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth().imePadding().windowInsetsPadding(WindowInsets.navigationBars),
                        factory = {
                            DshWebView.obtain(activity).also { w ->
                                DshWebView.installThemeIfNeeded()
                                val authKey = "${DshManager.baseUrl}#${k.token}"
                                if (DshWebView.authedFor != authKey || w.url == null) {
                                    DshWebView.authedFor = authKey
                                    // GET /?token= mints the auth cookie and redirects to the app.
                                    w.loadUrl("${DshManager.baseUrl}/?token=${Uri.encode(k.token)}")
                                }
                            }
                        },
                    )
                }
            }
        }

        DshMenuSheet(
            visible = menuOpen,
            versions = versions,
            onDismiss = { menuOpen = false },
            onSandbox = { menuOpen = false; sandboxOpen = true },
        )
        SandboxSheet(visible = sandboxOpen, onDismiss = { sandboxOpen = false })
    }
}

@Composable
private fun DshMenuSheet(visible: Boolean, versions: DshVersions, onDismiss: () -> Unit, onSandbox: () -> Unit) {
    val c = ReliefTheme.colors
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "DeepSeek Harness") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text("版本", style = ReliefTheme.type.label, color = c.ink2)
            ListRow("DeepSeek Harness", icon = "spark", value = versions.dsh.ifBlank { "未安装" }, subtitle = when {
                versions.updateAvailable -> "有新版本 ${versions.latest}"
                versions.latest.isNotBlank() -> "已是最新"
                else -> "暂时无法检查更新"
            }, chevron = false)
            Divider()
            ListRow("移动前端插件", icon = "puzzle", value = versions.plugin.ifBlank { "未附带" }, subtitle = if (versions.pluginRange.isNotBlank()) "兼容 DeepSeek Harness ${versions.pluginRange}" else "当前使用 DeepSeek Harness 自带界面", chevron = false)
            Divider()
            ListRow("Node.js", icon = "cpu", value = versions.node.ifBlank { "—" }, chevron = false)
            if (versions.updateAvailable) {
                Spacer(Modifier.height(10.dp))
                Text("更新只替换程序文件，会话、设置和登录信息都会保留。更新前会检查与移动前端的兼容性，更新后需重启服务。", style = ReliefTheme.type.caption, color = c.ink2)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("更新到 ${versions.latest}", size = ButtonSize.Small, icon = "download", onClick = { DshManager.update(); onDismiss() })
            }
            Spacer(Modifier.height(16.dp))
            Text("服务", style = ReliefTheme.type.label, color = c.ink2)
            ListRow("重启服务", icon = "refresh", subtitle = "配置或服务商变更后需要重启才会生效", onClick = { DshManager.restartServer(); onDismiss(); Nav.back(); Nav.go(Route.Dsh) })
            Divider()
            ListRow("服务日志", icon = "list", onClick = { onDismiss(); Nav.go(Route.Service(DshManager.SERVICE)) })
            Divider()
            ListRow("服务商与模型", icon = "key", onClick = { onDismiss(); Nav.go(Route.Providers("dsh")) })
            Divider()
            ListRow("权限与沙箱", icon = "shield", subtitle = "在手机上需选择「完全访问」", onClick = onSandbox)
        }
    }
}

@Composable
private fun SandboxSheet(visible: Boolean, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "权限与沙箱") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("权限与沙箱", style = ReliefTheme.type.title)
            Text(
                "DeepSeek Harness 的「只读」和「工作区可写」依赖 Linux 沙箱（bubblewrap / Landlock）。HaniyaX 的工作区运行在 proot 中，这些沙箱不可用，工具调用会被拒绝执行。",
                style = ReliefTheme.type.body, color = c.ink2,
            )
            Text(
                "如需让它运行命令，请在 DeepSeek Harness 的「设置 → 通用 → 权限」中选择「完全访问」（danger-full-access）。审批策略保持「询问」时，每次执行前仍会请你确认。",
                style = ReliefTheme.type.body, color = c.ink2,
            )
            Text("完全访问意味着命令可读写整个工作区；HaniyaX 不会替你更改这一设置。", style = ReliefTheme.type.caption, color = c.amberInk)
            GlassButton("知道了", onClick = onDismiss)
        }
    }
}
