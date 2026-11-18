package dev.haniya.x.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.FieldLabel
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.tr
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.agents.AgentStatus
import dev.haniya.x.agents.LaunchRequest
import dev.haniya.x.core.Paths
import dev.haniya.x.projects.Project
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Navigation helpers shared by the home sheets and modules. Main thread only. */
internal object HomeActions {
    fun openTask(id: String) {
        Runtime.tasks.openTab(id)
        Runtime.tasks.focus(id)
        Nav.go(Route.Terminal(id))
    }

    /** Starts a login shell in [path] and opens it. Returns a Chinese error message on failure. */
    fun openShell(path: String): String? {
        if (!Paths.host(path).isDirectory) return "目录不存在：${Fmt.shortPath(path)}"
        return try {
            val id = Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = Fmt.folderName(path), cwd = path))
            App.projects.touch(path)
            Nav.go(Route.Terminal(id))
            null
        } catch (e: Exception) {
            "无法打开终端：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Project actions: terminal, agents, default entry, pin, remove. */
@Composable
fun ProjectSheet(
    project: Project?,
    tasks: List<TaskRecord>,
    start: StartTarget,
    onDismiss: () -> Unit,
    onLaunchAgent: (LaunchRequest) -> Unit,
) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val status by App.agents.status.collectAsState()
    // Keep the last project while the sheet animates out.
    var shown by remember { mutableStateOf<Project?>(null) }
    if (project != null) shown = project
    val p = shown
    GlassSheet(visible = project != null, onDismiss = onDismiss, title = p?.name) {
        if (p == null) return@GlassSheet
        Text(Fmt.shortPath(p.path), style = monoStyle, color = c.ink2, translate = false)
        Spacer(Modifier.height(10.dp))

        ActionRow("terminal", "在此打开终端", onClick = {
            val err = HomeActions.openShell(p.path)
            if (err != null) toast.show(err, icon = "warn") else onDismiss()
        }, subtitle = "新的 Shell 标签，工作目录为此项目")
        Divider()
        ActionRow("folder", "在文件中查看", onClick = {
            onDismiss()
            Nav.go(Route.Files(p.path))
        }, subtitle = "浏览、导入或分享项目里的文件")

        val running = tasks.filter { it.status == TaskStatus.Running && TaskList.inProject(it, p.path) }
        running.forEach { t ->
            Divider()
            ItemRow(
                title = t.title,
                subtitle = tr("回到正在运行的会话"),
                leading = { IconTile(if (t.kind == TaskKind.Agent) "bot" else "terminal", size = 34.dp, filled = false) },
                trailing = { Badge("运行中", tone = BadgeTone.Accent) },
                onClick = { HomeActions.openTask(t.id); onDismiss() },
            )
        }

        if (App.agents.agents.isNotEmpty()) {
            FieldLabel("用智能体打开")
            App.agents.agents.forEachIndexed { i, a ->
                if (i > 0) Divider()
                val sub = when (val s = status[a.id]) {
                    is AgentStatus.Installed -> "已安装 · ${s.version}"
                    AgentStatus.NotInstalled -> "未安装"
                    is AgentStatus.Busy -> "正在安装或更新"
                    is AgentStatus.Error -> s.message
                    else -> null
                }
                ActionRow(
                    iconOr(a.icon, "bot"), "启动 ${a.name}", subtitle = sub,
                    onClick = { onLaunchAgent(LaunchRequest(agentId = a.id, projectPath = p.path)); onDismiss() },
                    trailing = if (p.lastAgent == a.id) ({ Badge("上次使用", tone = BadgeTone.Neutral) }) else null,
                )
            }
        }

        FieldLabel("管理")
        val isStart = start is StartTarget.Project && start.path == p.path
        ActionRow(
            "home", if (isStart) "已是打开 App 时的默认入口" else "设为打开 App 时的默认入口",
            subtitle = if (isStart) "点按改回打开首页" else "下次打开 HaniyaX 时直接进入这个项目",
            onClick = {
                if (isStart) {
                    HomeStore.setStart(StartTarget.Home)
                    toast.show("打开 App 时会显示首页")
                } else {
                    HomeStore.setStart(StartTarget.Project(p.path))
                    toast.show("下次打开时进入 ${p.name}")
                }
            },
            trailing = if (isStart) ({ HIcon("check", size = 18.dp, brush = c.brush) }) else null,
        )
        Divider()
        ActionRow("pin", if (p.pinned) "取消固定" else "固定在最前", onClick = {
            App.projects.setPinned(p.path, !p.pinned)
            onDismiss()
        })
        Divider()
        ActionRow("eyeOff", "从列表移除", subtitle = "只从首页移除，不会删除文件", onClick = {
            val path = p.path
            App.projects.remove(path)
            HomeStore.clearStartIf(StartTarget.Project(path))
            onDismiss()
            toast.show("已从列表移除", undo = { App.projects.add(path) })
        })
    }
}

/** Process-wide state of the 新建项目 flow, so a long clone survives leaving the home screen. */
internal object ProjectCreator {
    sealed interface State {
        data object Idle : State
        data class Running(val name: String, val gitUrl: String?, val startedAt: Long) : State
        data class Failed(val message: String) : State
        data class Done(val path: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun create(name: String, gitUrl: String?) {
        if (_state.value is State.Running) return
        val display = name.trim().ifEmpty { gitUrl?.let { ProjectLogic.repoNameFromUrl(it) } ?: "" }
        _state.value = State.Running(display, gitUrl, System.currentTimeMillis())
        App.scope.launch {
            val r = try {
                App.projects.create(name.trim(), gitUrl)
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.value = r.fold({ State.Done(it) }, { State.Failed(it.message ?: "创建失败") })
        }
    }

    fun clear() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }
}

/** 新建项目: a folder under /root/projects, optionally cloned from Git. */
@Composable
fun NewProjectSheet(visible: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val focus = LocalFocusManager.current
    val state by ProjectCreator.state.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        val s = state
        if (s is ProjectCreator.State.Done) {
            ProjectCreator.clear()
            name = ""; url = ""
            toast.show("已创建 ${Fmt.folderName(s.path)}")
            onCreated(s.path)
        }
    }

    val trimmedUrl = url.trim()
    val urlError = ProjectLogic.validateGitUrl(trimmedUrl)
    val effectiveName = name.trim().ifEmpty { if (trimmedUrl.isNotEmpty()) ProjectLogic.repoNameFromUrl(trimmedUrl) ?: "" else "" }
    val nameError = if (name.isNotBlank()) ProjectLogic.validateName(name) else null
    val canCreate = effectiveName.isNotEmpty() && nameError == null && urlError == null && ProjectLogic.validateName(effectiveName) == null

    fun submit() {
        if (!canCreate) return
        focus.clearFocus()
        ProjectCreator.create(name, trimmedUrl.ifEmpty { null })
    }

    GlassSheet(visible = visible, onDismiss = { focus.clearFocus(); onDismiss() }, title = "新建项目") {
        when (val s = state) {
            is ProjectCreator.State.Running -> {
                val now = rememberNow(1_000)
                Text(
                    if (s.gitUrl != null) "正在克隆到 ${Paths.GUEST_PROJECTS}/${s.name}" else "正在创建 ${s.name}",
                    style = ReliefTheme.type.bodyMedium, translate = false,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "已用时 ${Fmt.duration(now - s.startedAt)}。可以先回到首页，完成后项目会出现在列表里。",
                    style = ReliefTheme.type.caption, color = c.ink2,
                )
                Spacer(Modifier.height(18.dp))
                GlassButton("在后台继续", onDismiss, Modifier.fillMaxWidth())
            }
            else -> {
                FieldLabel("项目名称")
                GlassField(
                    name, { name = it; ProjectCreator.clear() },
                    placeholder = ProjectLogic.repoNameFromUrl(trimmedUrl) ?: "my-app",
                    imeAction = ImeAction.Next,
                )
                if (nameError != null) Hint(nameError, warn = true)
                FieldLabel("Git 仓库地址（可选）")
                GlassField(
                    url, { url = it; ProjectCreator.clear() },
                    placeholder = "https://github.com/…",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (urlError != null) Hint(urlError, warn = true)
                else if (effectiveName.isNotEmpty() && nameError == null) Hint("将创建 ${Paths.GUEST_PROJECTS}/$effectiveName", warn = false)
                if (s is ProjectCreator.State.Failed) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        HIcon("info", size = 18.dp, tint = c.amberInk)
                        Text(s.message, style = ReliefTheme.type.body, color = c.amberInk, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    if (trimmedUrl.isEmpty()) "创建" else "克隆并创建", { submit() },
                    Modifier.fillMaxWidth(), enabled = canCreate,
                )
                Spacer(Modifier.height(12.dp))
                GlassButton("选择已有文件夹", {
                    focus.clearFocus()
                    onDismiss()
                    Nav.go(Route.Files(Paths.GUEST_PROJECTS, pickProject = true))
                }, Modifier.fillMaxWidth(), icon = "folder")
                Hint("可以选择工作区或手机存储中的文件夹作为项目", warn = false)
            }
        }
    }
}

@Composable
private fun Hint(text: String, warn: Boolean) {
    val c = ReliefTheme.colors
    Text(
        text, style = ReliefTheme.type.caption, color = if (warn) c.amberInk else c.ink2,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

/** Task actions: back to the session, resume / restart, default entry, end, forget. */
@Composable
fun TaskSheet(task: TaskRecord?, start: StartTarget, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    var shown by remember { mutableStateOf<TaskRecord?>(null) }
    if (task != null) shown = task
    val t = shown
    var confirmEnd by remember { mutableStateOf(false) }

    GlassSheet(visible = task != null, onDismiss = onDismiss, title = t?.title) {
        if (t == null) return@GlassSheet
        val now = rememberNow(30_000)
        val time = if (t.status == TaskStatus.Running) "已运行 ${Fmt.duration(now - t.startedAt)}" else "${Fmt.ago(now - t.startedAt)}开始"
        Text(
            "${Fmt.shortPath(t.cwd)} · ${TaskList.statusLabel(t)} · $time",
            style = ReliefTheme.type.caption,
            color = if (t.status == TaskStatus.KilledBySystem) c.amberInk else c.ink2,
            translate = false,
        )
        Spacer(Modifier.height(10.dp))

        val running = t.status == TaskStatus.Running
        ActionRow("terminal", if (running) "回到会话" else "查看记录", onClick = { HomeActions.openTask(t.id); onDismiss() })
        if (!running) {
            if (t.resumeCommand != null) {
                Divider()
                ActionRow("history", "恢复会话", subtitle = "用智能体的续接命令接着上次的对话", onClick = {
                    runCatching { Runtime.tasks.resume(t.id) }
                        .onSuccess { id -> HomeActions.openTask(id); onDismiss() }
                        .onFailure { toast.show("无法恢复：${it.message ?: "未知错误"}", icon = "warn") }
                })
            }
            Divider()
            ActionRow("refresh", "重新启动", subtitle = "在同一目录用相同命令开一个新任务", onClick = {
                runCatching { Runtime.tasks.restart(t.id) }
                    .onSuccess { id -> HomeActions.openTask(id); onDismiss() }
                    .onFailure { toast.show("无法重新启动：${it.message ?: "未知错误"}", icon = "warn") }
            })
        }
        Divider()
        val isStart = start is StartTarget.Task && start.taskId == t.id
        ActionRow(
            "home", if (isStart) "已是打开 App 时的默认入口" else "设为默认入口",
            subtitle = if (isStart) "点按改回打开首页" else "下次打开 HaniyaX 时直接回到这个任务",
            onClick = {
                if (isStart) {
                    HomeStore.setStart(StartTarget.Home); toast.show("打开 App 时会显示首页")
                } else {
                    HomeStore.setStart(StartTarget.Task(t.id)); toast.show("下次打开时回到这个任务")
                }
            },
            trailing = if (isStart) ({ HIcon("check", size = 18.dp, brush = c.brush) }) else null,
        )
        Divider()
        if (running) {
            ActionRow("stop", "结束任务", titleColor = c.danger, subtitle = "结束进程，未保存的内容会丢失", onClick = { confirmEnd = true })
        } else {
            ActionRow("trash", "移除记录", subtitle = "只删除这条记录", onClick = {
                Runtime.tasks.forget(t.id)
                HomeStore.clearStartIf(StartTarget.Task(t.id))
                onDismiss()
                toast.show("已移除记录")
            })
        }
    }

    ConfirmSheet(
        visible = confirmEnd && task != null,
        title = "结束这个任务？",
        message = "将结束「${t?.title ?: ""}」中的进程，未保存的内容会丢失。只想离开的话直接返回即可，任务会继续运行。",
        confirmText = "结束任务",
        onConfirm = {
            confirmEnd = false
            t?.let { Runtime.tasks.terminate(it.id) }
            onDismiss()
            toast.show("任务已结束")
        },
        onDismiss = { confirmEnd = false },
    )
}
