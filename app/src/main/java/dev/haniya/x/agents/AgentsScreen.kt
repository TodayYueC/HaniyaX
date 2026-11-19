package dev.haniya.x.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.CheckCircle
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.ProgressBar
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.runtime.JobSnapshot
import dev.haniya.x.runtime.JobState
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal val agentMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

/** Route.Agents — install, update and start Claude Code, Codex and OpenCode. */
@Composable
fun AgentsScreen() {
    val status by App.agents.status.collectAsState()
    val jobs by Runtime.jobs.jobs.collectAsState()
    val tasks by Runtime.tasks.tasks.collectAsState()
    val lastJobsFlow: StateFlow<Map<String, String>> = remember {
        AgentManager.instance?.lastJobs ?: MutableStateFlow(emptyMap())
    }
    val lastJobs by lastJobsFlow.collectAsState()

    var logJobId by remember { mutableStateOf<String?>(null) }
    var updateFor by remember { mutableStateOf<String?>(null) }
    var launch by remember { mutableStateOf<LaunchRequest?>(null) }

    LaunchedEffect(Unit) { App.agents.refresh() }

    Page(
        title = "智能体",
        caption = "按需安装，在项目里打开真实的终端会话",
        actions = { IconCircleButton("refresh", contentDescription = "重新检查", onClick = { App.agents.refresh() }) },
        overlay = {
            val logJob = logJobId?.let { id -> jobs.firstOrNull { it.id == id } }
            LogSheet(job = logJob, visible = logJobId != null, onDismiss = { logJobId = null })
            UpdateSheet(
                agentId = updateFor,
                status = updateFor?.let { status[it] as? AgentStatus.Installed },
                running = updateFor?.let { id -> tasks.filter { it.agentId == id && it.status == TaskStatus.Running } }.orEmpty(),
                onDismiss = { updateFor = null },
            )
            AgentLaunchSheet(launch) { launch = null }
        },
    ) {
        DshEntry()
        App.agents.agents.forEach { info ->
            val adapter = Adapters.find(info.id)
            val s = status[info.id]
            val jobId = (s as? AgentStatus.Busy)?.jobId ?: lastJobs[info.id]
            AgentCard(
                info = info,
                adapter = adapter,
                status = s,
                job = jobId?.let { id -> jobs.firstOrNull { it.id == id } },
                running = tasks.count { it.agentId == info.id && it.status == TaskStatus.Running },
                onInstall = { App.agents.install(info.id) },
                onUpdate = { updateFor = info.id },
                onLaunch = { launch = LaunchRequest(agentId = info.id) },
                onShowLog = { logJobId = it },
            )
        }
        LoginCard()
    }
}

@Composable
private fun AgentCard(
    info: AgentInfo,
    adapter: AgentAdapter?,
    status: AgentStatus?,
    job: JobSnapshot?,
    running: Int,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onShowLog: (String) -> Unit,
) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val context = LocalContext.current

    // A calm confirmation when a job this screen watched finishes well.
    var watched by remember(job?.id) { mutableStateOf(false) }
    LaunchedEffect(job?.id, job?.state) {
        when (job?.state) {
            JobState.Running -> watched = true
            JobState.Succeeded -> if (watched) {
                watched = false
                val v = job.result?.takeIf { it.isNotBlank() }
                toast.show(if (v != null) "${info.name} $v 已就绪" else "${info.name} 已就绪")
            }
            else -> Unit
        }
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(info.icon.ifBlank { "bot" }, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.name, style = ReliefTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                    Text(info.vendor, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, translate = false)
                }
                Text(
                    AgentUiLogic.statusLine(status), style = ReliefTheme.type.caption, color = c.ink2,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (running > 0) Badge("$running 个会话", tone = BadgeTone.Accent)
        }

        if (status is AgentStatus.Installed && status.latest != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.latestVerified) Badge("可更新", tone = BadgeTone.Accent) else Badge("新版本未经验证", tone = BadgeTone.Amber)
                Text("新版本 ${status.latest}", style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1)
            }
        }

        Spacer(Modifier.height(16.dp))
        when {
            status is AgentStatus.Busy -> JobProgress(job, adapter?.installSteps ?: 5, onShowLog)
            else -> {
                val failed = job?.takeIf { it.state == JobState.Failed }
                if (failed != null) {
                    FailureNotice(
                        job = failed,
                        onRetry = {
                            val kind = AgentManager.instance?.lastJobKind(info.id)
                            if (kind == AgentManager.KIND_UPDATE && status is AgentStatus.Installed) App.agents.update(info.id)
                            else App.agents.install(info.id)
                        },
                        onCopy = {
                            copyText(context, AgentUiLogic.logText(failed.title, failed.error, failed.log))
                            toast.show("日志已复制")
                        },
                        onShowLog = { onShowLog(failed.id) },
                        onClose = { AgentManager.instance?.dismissJob(info.id) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (status is AgentStatus.Error) {
                    Notice(status.message)
                    Spacer(Modifier.height(16.dp))
                }
                ActionsRow(info, status, failed != null, onInstall, onUpdate, onLaunch)
            }
        }
    }
}

@Composable
private fun ActionsRow(
    info: AgentInfo,
    status: AgentStatus?,
    hasFailure: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onLaunch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            is AgentStatus.Installed -> {
                PrimaryButton("启动", onClick = onLaunch, size = ButtonSize.Small, icon = "play")
                if (status.latest != null) GlassButton("更新", onClick = onUpdate, size = ButtonSize.Small, icon = "download")
            }
            AgentStatus.NotInstalled -> if (!hasFailure) {
                PrimaryButton("安装", onClick = onInstall, size = ButtonSize.Small, icon = "download")
            }
            is AgentStatus.Error -> GlassButton("重试", onClick = { App.agents.refresh(info.id) }, size = ButtonSize.Small, icon = "refresh")
            else -> GlassButton("检查中…", onClick = {}, size = ButtonSize.Small, enabled = false)
        }
        Spacer(Modifier.weight(1f))
        // Antigravity manages its sign-in and model provider itself (~/.gemini/antigravity-cli/settings.json).
        if (info.id != "antigravity") TextLink("服务商", onClick = { Nav.go(Route.Providers(info.id)) })
    }
}

/** Inline progress of a running install/update: step checklist, overall bar, the last log lines. */
@Composable
private fun JobProgress(job: JobSnapshot?, expectedSteps: Int, onShowLog: (String) -> Unit) {
    val c = ReliefTheme.colors
    if (job == null) {
        Text("准备中…", style = ReliefTheme.type.body, color = c.ink2)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        job.steps.forEachIndexed { i, step ->
            val current = i == job.steps.lastIndex && !step.done
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step.failed) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { HIcon("warn", size = 18.dp, tint = c.amber) }
                } else {
                    CheckCircle(checked = step.done, onClick = null, size = 20.dp, label = step.label)
                }
                Text(
                    step.label,
                    style = if (current) ReliefTheme.type.label else ReliefTheme.type.body.copy(fontSize = 13.sp),
                    color = if (current) c.ink else c.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (job.steps.isEmpty()) Text("准备中…", style = ReliefTheme.type.body, color = c.ink2)
        ProgressBar(AgentUiLogic.overallProgress(job.steps, job.progress, expectedSteps), Modifier.padding(top = 4.dp))
        val tail = AgentUiLogic.tail(job.log, 6)
        if (tail.isNotEmpty()) LogBox(tail.joinToString("\n"), maxLines = 6)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextLink("查看完整日志", onClick = { onShowLog(job.id) })
            Spacer(Modifier.weight(1f))
            TextLink("停止", onClick = { Runtime.jobs.cancel(job.id) }, color = c.ink2)
        }
    }
}

@Composable
private fun FailureNotice(job: JobSnapshot, onRetry: () -> Unit, onCopy: () -> Unit, onShowLog: () -> Unit, onClose: () -> Unit) {
    val c = ReliefTheme.colors
    Column(
        Modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HIcon("warn", size = 18.dp, tint = c.amber, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (job.title.startsWith("更新")) "这次更新没有完成" else "这次安装没有完成",
                    style = ReliefTheme.type.label, color = c.amberInk,
                )
                Text(
                    job.error ?: "安装脚本返回异常（退出码 ${job.exitCode ?: "未知"}）",
                    style = ReliefTheme.type.body, color = c.ink,
                )
            }
            SmallRoundButton("close", contentDescription = "收起", onClick = onClose)
        }
        val tail = AgentUiLogic.tail(job.log, 3)
        if (tail.isNotEmpty()) Text(tail.joinToString("\n"), style = agentMono, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis, translate = false)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassButton("重试", onClick = onRetry, size = ButtonSize.Small, icon = "refresh")
            TextLink("复制日志", onClick = onCopy)
            TextLink("完整日志", onClick = onShowLog)
        }
    }
}

/** Amber notice: something needs attention, never alarm red. */
@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier) {
    val c = ReliefTheme.colors
    Row(
        modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HIcon("warn", size = 18.dp, tint = c.amber, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = ReliefTheme.type.body, color = c.amberInk, modifier = Modifier.weight(1f))
    }
}

/** Inset monospace well for command output. */
@Composable
internal fun LogBox(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val c = ReliefTheme.colors
    Box(modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, style = agentMono, color = c.ink2, maxLines = maxLines, overflow = TextOverflow.Ellipsis, translate = false)
    }
}

@Composable
private fun LogSheet(job: JobSnapshot?, visible: Boolean, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val context = LocalContext.current
    var shown by remember { mutableStateOf<JobSnapshot?>(null) }
    if (job != null) shown = job
    val j = shown
    GlassSheet(visible = visible, onDismiss = onDismiss, title = j?.title ?: "日志") {
        if (j == null) return@GlassSheet
        val state = when (j.state) {
            JobState.Running -> "进行中"
            JobState.Succeeded -> "已完成"
            JobState.Failed -> "没有完成"
            JobState.Cancelled -> "已停止"
        }
        Text(
            state + (j.exitCode?.let { " · 退出码 $it" } ?: "") + " · ${j.log.size} 行",
            style = ReliefTheme.type.caption, color = c.ink2,
        )
        if (!j.error.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Notice(j.error)
        }
        Spacer(Modifier.height(12.dp))
        val text = j.log.joinToString("\n") { AgentUiLogic.clean(it) }.ifBlank { "暂无输出" }
        Box(Modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(12.dp)) {
            SelectionContainer { Text(text, style = agentMono, color = c.ink, translate = false) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("复制日志", onClick = {
                copyText(context, AgentUiLogic.logText(j.title, j.error, j.log))
                toast.show("日志已复制")
            }, size = ButtonSize.Small, icon = "copy")
        }
    }
}

/** Confirmation before a user-initiated update. */
@Composable
private fun UpdateSheet(agentId: String?, status: AgentStatus.Installed?, running: List<TaskRecord>, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    var heldId by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf<AgentStatus.Installed?>(null) }
    if (agentId != null && status != null) { heldId = agentId; held = status }
    val adapter = Adapters.find(heldId)
    val s = held
    GlassSheet(visible = agentId != null && status != null, onDismiss = onDismiss, title = adapter?.let { "更新 ${it.info.name}" }) {
        if (adapter == null || s == null) return@GlassSheet
        Text(
            "从 ${s.version.ifBlank { "当前版本" }} 更新到 ${s.latest ?: "最新版本"}",
            style = ReliefTheme.type.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        if (AgentUiLogic.updateNeedsWarning(s)) {
            Notice("新版本未经验证：HaniyaX 目前在 ${adapter.info.name} ${adapter.verifiedMajor}.x 上测试过，新的主版本可能有不兼容的变化。如遇问题，可以重新安装 ${s.version}。")
            Spacer(Modifier.height(12.dp))
        }
        if (s.source != AgentManager.SOURCE_HANIYAX) {
            Text(
                "当前版本是你自行安装的。更新会用官方方式安装一份新版本，原有安装保持不变；之后启动时优先使用新版本。",
                style = ReliefTheme.type.body, color = c.ink2,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (running.isNotEmpty()) {
            Text("正在运行的会话不会被中断，它们继续使用旧版本；新版本在之后新开的会话中生效。", style = ReliefTheme.type.body, color = c.ink2)
            Spacer(Modifier.height(8.dp))
            running.forEachIndexed { i, t ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile("terminal", size = 34.dp, filled = false)
                    Text(t.lastTitle ?: t.title, style = ReliefTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), translate = false)
                    Badge("运行中", tone = BadgeTone.Neutral)
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Text("新版本在之后新开的会话中生效。", style = ReliefTheme.type.body, color = c.ink2)
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HIcon("shield", size = 18.dp, brush = c.brush, modifier = Modifier.padding(top = 2.dp))
            Text(
                "设置、登录状态和历史会话都会保留，更新不会改动 ${adapter.keptPaths.joinToString("、")}。",
                style = ReliefTheme.type.body, color = c.ink2, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton(
                if (AgentUiLogic.updateNeedsWarning(s)) "仍要更新" else "更新",
                onClick = {
                    try {
                        App.agents.update(adapter.id)
                        onDismiss()
                    } catch (e: Exception) {
                        toast.show(e.message ?: "无法开始更新", icon = "warn")
                    }
                },
                modifier = Modifier.weight(1f),
                icon = "download",
            )
        }
    }
}

@Composable
private fun LoginCard() {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("登录与服务商", icon = "key")
        Spacer(Modifier.height(12.dp))
        Text(
            "官方账号登录在终端中完成：启动智能体后按它的提示登录即可。也可以在「服务商」中配置 API 地址、密钥和模型。",
            style = ReliefTheme.type.body, color = c.ink2,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "HaniyaX 关闭了智能体的自动更新，是否更新由你决定；更新时保留设置、登录和历史。",
            style = ReliefTheme.type.body, color = c.ink2,
        )
        Spacer(Modifier.height(12.dp))
        TextLink("管理服务商", onClick = { Nav.go(Route.Providers()) }, icon = "chevR")
    }
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("HaniyaX", text))
}

/** DeepSeek Harness lives with the agents: one row that opens its embedded workspace. */
@Composable
private fun DshEntry() {
    val c = ReliefTheme.colors
    val services by App.services.services.collectAsState()
    val online = services.any { (it.def.owner == "dsh" || it.def.name == "dsh") && it.status == dev.haniya.x.services.ServiceStatus.Running }
    dev.haniya.relief.component.GlassCard(onClick = { dev.haniya.x.ui.Nav.go(dev.haniya.x.ui.Route.Dsh) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile("whale", size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("DeepSeek Harness", style = ReliefTheme.type.title, maxLines = 1, translate = false)
                Text(if (online) "服务在线" else "DeepSeek 的开源智能体工作台", style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1)
            }
            dev.haniya.relief.icon.HIcon("chevR", size = 18.dp, tint = c.ink2)
        }
    }
}
