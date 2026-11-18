package dev.haniya.x.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.CheckCircle
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.FieldLabel
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.SwitchRow
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.component.pressable
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.projects.Project
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the caller already knows: a preselected agent and/or project. Missing parts are asked for in the sheet. */
data class LaunchRequest(val agentId: String? = null, val projectPath: String? = null)

private enum class LaunchStep { Agent, Project, Confirm }

/** Sessions shown individually in the history list. */
private const val HISTORY_ROWS = 8

/**
 * Start an agent in a project: choose agent (unless given) → choose project (unless given) → continue / pick / new
 * conversation, sandbox opt-in → launch. Visible while [request] != null. Place it in a screen-root Box.
 */
@Composable
fun AgentLaunchSheet(request: LaunchRequest?, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val status by App.agents.status.collectAsState()
    val projects by App.projects.projects.collectAsState()
    val fullAccessFlow: StateFlow<Map<String, Boolean>> = remember {
        AgentManager.instance?.fullAccess ?: MutableStateFlow(emptyMap())
    }
    val fullAccess by fullAccessFlow.collectAsState()

    // Keep the last request while the sheet animates out; a new request object starts a fresh flow.
    var held by remember { mutableStateOf<LaunchRequest?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    if (request != null && request !== held) {
        held = request
        generation++
    }
    val req = held

    var agentId by remember(generation) { mutableStateOf(req?.agentId) }
    var projectPath by remember(generation) { mutableStateOf(req?.projectPath) }
    var sessions by remember(generation) { mutableStateOf<List<AgentSession>?>(null) }
    var resume by remember(generation) { mutableStateOf<String?>(null) }
    var resumeTouched by remember(generation) { mutableStateOf(false) }
    var launching by remember(generation) { mutableStateOf(false) }

    val step = when {
        agentId == null -> LaunchStep.Agent
        projectPath == null -> LaunchStep.Project
        else -> LaunchStep.Confirm
    }

    // Sessions for the chosen agent + project.
    val chosenInstalled = status[agentId ?: ""] is AgentStatus.Installed
    LaunchedEffect(generation, agentId, projectPath, chosenInstalled) {
        val a = agentId
        val p = projectPath
        sessions = null
        if (a == null || p == null || !chosenInstalled) return@LaunchedEffect
        val found = withTimeoutOrNull(12_000) { App.agents.sessions(a, p) } ?: emptyList()
        sessions = found
        if (!resumeTouched) resume = AgentUiLogic.defaultResume(found)
    }

    val back: (() -> Unit)? = when (step) {
        LaunchStep.Agent -> null
        LaunchStep.Project -> if (req?.agentId == null) ({ agentId = null }) else null
        LaunchStep.Confirm -> when {
            req?.projectPath == null -> ({ projectPath = null; resumeTouched = false })
            req?.agentId == null -> ({ agentId = null; resumeTouched = false })
            else -> null
        }
    }
    val adapter = Adapters.find(agentId)
    val title = when (step) {
        LaunchStep.Agent -> "选择智能体"
        LaunchStep.Project -> "选择项目"
        LaunchStep.Confirm -> "启动 ${adapter?.info?.name ?: ""}".trim()
    }

    GlassSheet(visible = request != null, onDismiss = onDismiss, title = title, onBack = back) {
        if (req == null) return@GlassSheet
        when (step) {
            LaunchStep.Agent -> AgentStep(status, onPick = { agentId = it }, onInstall = { onDismiss(); Nav.go(Route.Agents) })
            LaunchStep.Project -> ProjectStep(
                projects = projects,
                agentId = agentId,
                onPick = { projectPath = it },
            )
            LaunchStep.Confirm -> {
                val a = adapter ?: return@GlassSheet
                val path = projectPath ?: return@GlassSheet
                val s = status[a.id]
                if (s !is AgentStatus.Installed) {
                    NotInstalledStep(a, s, onInstall = {
                        onDismiss()
                        if (Nav.current != Route.Agents) Nav.go(Route.Agents)
                    })
                    return@GlassSheet
                }
                ConfirmStep(
                    adapter = a,
                    path = path,
                    sessions = sessions,
                    resume = resume,
                    onResume = { resume = it; resumeTouched = true },
                    fullAccess = fullAccess[a.id] == true,
                    onFullAccess = { AgentManager.instance?.setFullAccess(a.id, it) },
                    launching = launching,
                    onLaunch = {
                        if (!launching) {
                            launching = true
                            scope.launch {
                                val exists = withContext(Dispatchers.IO) { Paths.host(path).isDirectory }
                                if (!exists) {
                                    launching = false
                                    toast.show("找不到目录 ${AgentUiLogic.shortPath(path)}", icon = "warn")
                                    return@launch
                                }
                                try {
                                    App.agents.launch(a.id, path, resume)
                                    onDismiss()
                                } catch (e: Exception) {
                                    toast.show(e.message ?: "无法启动 ${a.info.name}", icon = "warn")
                                } finally {
                                    launching = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentStep(status: Map<String, AgentStatus>, onPick: (String) -> Unit, onInstall: () -> Unit) {
    val c = ReliefTheme.colors
    App.agents.agents.forEachIndexed { i, a ->
        if (i > 0) Divider()
        val s = status[a.id]
        val installed = s is AgentStatus.Installed
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .then(if (installed) Modifier.pressable(pressedScale = 1f, onClick = { onPick(a.id) }) else Modifier)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(a.icon.ifBlank { "bot" }, size = 38.dp, filled = false)
            Column(Modifier.weight(1f)) {
                Text(a.name, style = ReliefTheme.type.bodyMedium, color = if (installed) c.ink else c.ink2, translate = false)
                Text(AgentUiLogic.statusLine(s), style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            }
            when {
                installed -> HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.7f))
                s == AgentStatus.NotInstalled || s is AgentStatus.Error -> TextLink("去安装", onClick = onInstall)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ProjectStep(projects: List<Project>, agentId: String?, onPick: (String) -> Unit) {
    val c = ReliefTheme.colors
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    PlaceRow(icon = "home", title = "主目录", subtitle = Paths.GUEST_HOME, onClick = { onPick(Paths.GUEST_HOME) })
    projects.filter { it.path != Paths.GUEST_HOME }.forEach { p ->
        Divider()
        PlaceRow(
            icon = if (p.pinned) "pin" else "folder",
            title = p.name,
            subtitle = AgentUiLogic.shortPath(p.path),
            badge = if (agentId != null && p.lastAgent == agentId) "上次使用" else null,
            onClick = { onPick(p.path) },
        )
    }
    Divider()
    PlaceRow(icon = "plus", title = "新建项目", subtitle = "在 ~/projects 下创建一个空文件夹", onClick = { newOpen = !newOpen; error = null })

    if (newOpen) {
        val submit = {
            val n = name.trim()
            if (n.isEmpty()) {
                error = "请输入项目名称"
            } else if (!creating) {
                creating = true
                error = null
                scope.launch {
                    val r = try {
                        App.projects.create(n)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    creating = false
                    r.fold(
                        onSuccess = { path -> name = ""; newOpen = false; onPick(path) },
                        onFailure = { e -> error = e.message ?: "创建失败" },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        GlassField(
            value = name,
            onValueChange = { name = it; error = null },
            placeholder = "项目名称，例如 my-app",
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Notice(error ?: "")
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (creating) "创建中…" else "创建并选择",
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !creating,
        )
    }
}

@Composable
private fun PlaceRow(icon: String, title: String, subtitle: String, onClick: () -> Unit, badge: String? = null) {
    val c = ReliefTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(pressedScale = 1f, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, size = 34.dp, filled = false)
        Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            Text(subtitle, style = agentMono, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
        }
        if (badge != null) Badge(badge, tone = BadgeTone.Neutral)
    }
}

@Composable
private fun NotInstalledStep(adapter: AgentAdapter, s: AgentStatus?, onInstall: () -> Unit) {
    val text = when (s) {
        is AgentStatus.Busy -> "${adapter.info.name} 正在安装，完成后即可启动。"
        null, AgentStatus.Unknown, AgentStatus.Checking -> "正在检查 ${adapter.info.name} 的安装状态…"
        else -> "${adapter.info.name} 尚未安装。安装只需一次，之后可以在任何项目中启动。"
    }
    Text(text, style = ReliefTheme.type.body, color = ReliefTheme.colors.ink2)
    Spacer(Modifier.height(16.dp))
    PrimaryButton("前往智能体页面", onClick = onInstall, modifier = Modifier.fillMaxWidth(), icon = "bot")
}

@Composable
private fun ConfirmStep(
    adapter: AgentAdapter,
    path: String,
    sessions: List<AgentSession>?,
    resume: String?,
    onResume: (String?) -> Unit,
    fullAccess: Boolean,
    onFullAccess: (Boolean) -> Unit,
    launching: Boolean,
    onLaunch: () -> Unit,
) {
    val c = ReliefTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile("folder", size = 34.dp, filled = false)
        Column(Modifier.weight(1f)) {
            Text(AgentUiLogic.folderName(path), style = ReliefTheme.type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            Text(AgentUiLogic.shortPath(path), style = agentMono, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
        }
    }

    when {
        sessions == null -> {
            Spacer(Modifier.height(14.dp))
            Text("正在读取历史会话…", style = ReliefTheme.type.caption, color = c.ink2)
        }
        sessions.isNotEmpty() -> {
            FieldLabel("会话")
            val latest = sessions.first()
            ChoiceRow(
                selected = resume == "",
                title = "继续上次会话",
                subtitle = latest.title + (latest.updatedAt?.let { " · " + AgentUiLogic.ago(it) } ?: ""),
                onClick = { onResume("") },
            )
            Divider()
            ChoiceRow(selected = resume == null, title = "新开会话", subtitle = null, onClick = { onResume(null) })
            val history = sessions.filter { it.id != null }.take(HISTORY_ROWS)
            if (history.size > 1) {
                FieldLabel("或选择一段历史会话")
                history.forEachIndexed { i, s ->
                    if (i > 0) Divider()
                    ChoiceRow(
                        selected = resume == s.id,
                        title = s.title,
                        subtitle = s.updatedAt?.let { AgentUiLogic.ago(it) },
                        onClick = { onResume(s.id) },
                    )
                }
                if (sessions.size > history.size) {
                    Text(
                        "更早的会话可以在 ${adapter.info.name} 里用它自己的恢复命令查看。",
                        style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        else -> Unit
    }

    FieldLabel("运行方式")
    if (adapter.supportsFullAccess) {
        SwitchRow(title = "沙箱不可用时以完全访问模式运行", checked = fullAccess, onCheckedChange = onFullAccess)
    }
    Text(adapter.sandboxNote, style = ReliefTheme.type.caption, color = c.ink2)

    Spacer(Modifier.height(20.dp))
    val label = when {
        launching -> "正在启动…"
        resume == null -> "新开会话"
        resume.isEmpty() -> "继续上次会话"
        else -> "恢复这段会话"
    }
    PrimaryButton(label, onClick = onLaunch, modifier = Modifier.fillMaxWidth(), icon = "play", enabled = !launching)
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    val c = ReliefTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable(pressedScale = 1f, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckCircle(checked = selected, onClick = null, size = 22.dp, label = title)
        Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
            if (subtitle != null) Text(subtitle, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
        }
    }
}
