package dev.haniya.x.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.CheckCircle
import dev.haniya.relief.component.Chevron
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.Expandable
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.IconChip
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.ProgressBar
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.component.pressable
import dev.haniya.relief.theme.NumStyles
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.ui.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** First-run / installing / failed states of the workspace. Ready is routed by MainActivity. */
@Composable
fun WorkspaceSetupScreen() {
    val ws by Runtime.workspace.state.collectAsState()
    val impl = Runtime.workspace as? WorkspaceImpl
    val fallback = remember { MutableStateFlow(InstallProgressView(emptySet(), null, null)) }
    val prog by (impl?.progress ?: fallback).collectAsState()
    val freeBytes = remember(ws is WorkspaceState.Installing) {
        runCatching { StatFs(Paths.files.path).availableBytes }.getOrNull()
    }
    val start: () -> Unit = {
        if (Runtime.workspace.state.value !is WorkspaceState.Installing) App.scope.launch { Runtime.workspace.install() }
    }
    // Steps finished before the app was closed are kept; installation resumes from the first unfinished one.
    val resumable = prog.done.isNotEmpty()

    val caption = when (val s = ws) {
        is WorkspaceState.Installing -> {
            val idx = (InstallStep.byLabel(s.step) ?: prog.current)?.ordinal ?: 0
            "正在准备 · 第 ${idx + 1}/${InstallStep.entries.size} 步"
        }
        is WorkspaceState.Failed -> "暂时没能完成，可以重试"
        else -> "首次使用前的准备"
    }

    Page(title = "创建工作区", caption = caption, onBack = null) {
        IntroCard()
        when (val s = ws) {
            WorkspaceState.NotInstalled, WorkspaceState.Ready -> {
                ContentsCard(freeBytes)
                if (resumable) StepsCard(prog, current = null, progress = null)
                PrimaryButton(
                    if (resumable) "继续创建工作区" else "创建工作区",
                    onClick = start, modifier = Modifier.fillMaxWidth(), icon = "play",
                )
                Text(
                    if (resumable) "已完成的步骤会保留，从中断处继续。需要联网下载基础软件。"
                    else "需要联网下载基础软件。过程中可以离开此页面，工作区会在后台继续准备。",
                    style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            is WorkspaceState.Installing -> {
                StepsCard(prog, current = InstallStep.byLabel(s.step) ?: prog.current, progress = s.progress)
                Text(
                    "可以离开此页面，工作区会在后台继续准备。",
                    style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                LogCard(s.log, initiallyOpen = false)
            }
            is WorkspaceState.Failed -> {
                FailedCard(s, onRetry = start)
                StepsCard(prog, current = null, progress = null)
                LogCard(s.log, initiallyOpen = true)
            }
        }
    }
}

@Composable
private fun IntroCard() {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("一套 Linux 环境", icon = "terminal", caption = "终端、Agent、项目与后台服务共用一套 Linux 环境")
        Spacer(Modifier.height(14.dp))
        Text(
            "HaniyaX 会在应用内准备一个完整的 Debian 系统，开箱即用。",
            style = ReliefTheme.type.body, color = c.ink,
        )
        Spacer(Modifier.height(12.dp))
        Feature("terminal", "多标签终端，离开页面后任务继续运行")
        Feature("bot", "按需安装 Codex、Claude Code、OpenCode")
        Feature("server", "后台服务与日志，随时查看与启停")
    }
}

@Composable
private fun Feature(icon: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconChip(icon, size = 26.dp)
        Text(text, style = ReliefTheme.type.body, color = ReliefTheme.colors.ink2)
    }
}

@Composable
private fun ContentsCard(freeBytes: Long?) {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("将要准备的内容", icon = "download")
        Spacer(Modifier.height(10.dp))
        KeyLine("系统镜像", "随应用附带，无需下载")
        Divider()
        KeyLine("基础软件", "git、curl、ripgrep 等，从 Debian 软件源下载")
        Divider()
        KeyLine("完成后占用", "约 250 MB")
        Divider()
        KeyLine("当前可用空间", freeBytes?.let(::formatBytes) ?: "无法读取")
        if (freeBytes != null && freeBytes < LOW_SPACE) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge("空间偏少", tone = BadgeTone.Amber)
                Text("建议至少保留 1 GB 可用空间", style = ReliefTheme.type.caption, color = c.ink2)
            }
        }
    }
}

@Composable
private fun KeyLine(key: String, value: String) {
    val c = ReliefTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = ReliefTheme.type.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        Text(
            value, style = ReliefTheme.type.body, color = c.ink2, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 156.dp),
        )
    }
}

@Composable
private fun StepsCard(prog: InstallProgressView, current: InstallStep?, progress: Float?) {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("准备进度", icon = "list")
        Spacer(Modifier.height(8.dp))
        InstallStep.entries.forEachIndexed { i, step ->
            if (i > 0) Divider()
            val done = step in prog.done
            val running = step == current && !done
            val failed = step == prog.failed
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckCircle(checked = done, onClick = null, size = 24.dp, label = step.label)
                    Text(
                        step.label,
                        style = if (running) ReliefTheme.type.bodyMedium else ReliefTheme.type.body,
                        color = if (done || running || failed) c.ink else c.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        failed -> Badge("未完成", tone = BadgeTone.Amber)
                        running && progress != null -> Text("${(progress * 100).toInt()}%", style = NumStyles.caption, color = c.mid)
                        running -> Text("进行中", style = ReliefTheme.type.caption, color = c.mid)
                    }
                }
                if (running && progress != null) {
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(progress, modifier = Modifier.padding(start = 36.dp), height = 8.dp)
                }
            }
        }
    }
}

@Composable
private fun FailedCard(s: WorkspaceState.Failed, onRetry: () -> Unit) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    GlassCard {
        CardHeader("暂时没能完成", icon = "info", caption = "已完成的步骤会保留，重试时从中断处继续")
        Spacer(Modifier.height(12.dp))
        SelectionContainer { Text(s.message, style = ReliefTheme.type.body, color = c.ink) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("重试", onClick = onRetry, icon = "refresh", modifier = Modifier.weight(1f))
            GlassButton(
                "复制日志", icon = "copy", modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        val full = withContext(Dispatchers.IO) {
                            runCatching { (Runtime.workspace as? WorkspaceImpl)?.installLogFile?.takeIf { it.isFile }?.readText() }.getOrNull()
                        }
                        val text = buildString {
                            append(s.message).append("\n\n")
                            append(full?.takeLast(200_000) ?: s.log.joinToString("\n"))
                        }
                        val ok = runCatching {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("HaniyaX 安装日志", text))
                        }.isSuccess
                        toast.show(if (ok) "日志已复制" else "无法访问剪贴板", icon = if (ok) "check" else "info")
                    }
                },
            )
        }
    }
}

@Composable
private fun LogCard(log: List<String>, initiallyOpen: Boolean) {
    val c = ReliefTheme.colors
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = { open = !open }, pressedScale = 1f).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("安装日志", style = ReliefTheme.type.h3, modifier = Modifier.weight(1f))
            Text(if (log.isEmpty()) "暂无输出" else "${log.size} 行", style = ReliefTheme.type.caption, color = c.ink2)
            Chevron(open)
        }
        Expandable(open) {
            val lines = log.takeLast(200)
            val scroll = rememberScrollState()
            LaunchedEffect(lines.size, lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
            Box(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                    .fillMaxWidth()
                    .height(260.dp)
                    .insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp)
                    .verticalScroll(scroll)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        if (lines.isEmpty()) "暂无输出" else lines.joinToString("\n"),
                        style = ReliefTheme.type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                        color = c.ink2,
                        translate = false,
                    )
                }
            }
        }
    }
}

private const val LOW_SPACE = 1L shl 30

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} MB"
    else -> "${b shr 10} KB"
}
