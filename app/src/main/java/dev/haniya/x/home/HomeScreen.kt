package dev.haniya.x.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.agents.AgentLaunchSheet
import dev.haniya.x.agents.AgentStatus
import dev.haniya.x.agents.LaunchRequest
import dev.haniya.x.plugins.PluginSummary
import dev.haniya.x.plugins.rememberPluginImporter
import dev.haniya.x.projects.Project
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.services.ServiceManager
import dev.haniya.x.services.ServiceStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import dev.haniya.x.ui.Space
import java.time.LocalTime

/**
 * Home, organised around 继续工作: greeting + workspace summary, the three fixed entry tiles, then the
 * modules in HomeLayout order (pinned first). Long-press a module or tap 编辑 to reorder / hide / pin.
 */
@Composable
fun HomeScreen() {
    val layout by HomeStore.layout.collectAsState()
    val projects by App.projects.projects.collectAsState()
    val tasks by Runtime.tasks.tasks.collectAsState()
    val services by App.services.services.collectAsState()
    val serviceErrors by remember { ServiceManager.errorsOf(App.services) }.collectAsState()
    val plugins by App.plugins.plugins.collectAsState()
    val agentStatus by App.agents.status.collectAsState()
    val haptics = LocalHapticFeedback.current

    var editing by rememberSaveable { mutableStateOf(false) }
    var projectPath by rememberSaveable { mutableStateOf<String?>(null) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var newProject by rememberSaveable { mutableStateOf(false) }
    var launch by remember { mutableStateOf<LaunchRequest?>(null) }
    val importCard = rememberPluginImporter()

    LaunchedEffect(Unit) { App.projects.refresh() }
    BackHandler(enabled = editing) { editing = false }

    val pluginById = remember(plugins) { plugins.associateBy { it.id } }
    val available: (String) -> Boolean = { id ->
        val pid = HomeLayoutOps.pluginIdOf(id)
        if (pid != null) pid in pluginById else id in HomeLayoutOps.BUILTIN
    }
    // Services have their own tab now; the home page keeps tasks, recent projects and cards.
    val onHome: (String) -> Boolean = { id -> id != HomeLayoutOps.SERVICES && available(id) }
    val visible = HomeLayoutOps.visible(layout, onHome)
    val hidden = HomeLayoutOps.hidden(layout, onHome)
    val agentName: (String) -> String = { id -> App.agents.agents.firstOrNull { it.id == id }?.name ?: id }
    val enterEdit = {
        if (!editing) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            editing = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.cardGap),
        ) {
            HomeHeader(
                runningTasks = TaskList.runningCount(tasks),
                onlineServices = services.count { it.status == ServiceStatus.Running },
                editing = editing,
                onToggleEdit = { editing = !editing },
            )
            visible.forEach { m ->
                key(m.id) {
                    ModuleSlot(
                        title = moduleTitle(m.id, pluginById),
                        builtin = m.id in HomeLayoutOps.BUILTIN,
                        module = m,
                        editing = editing,
                        canUp = HomeLayoutOps.canMove(layout, m.id, -1, onHome),
                        canDown = HomeLayoutOps.canMove(layout, m.id, +1, onHome),
                        onMove = { d -> HomeStore.move(m.id, d, onHome) },
                        onPin = { HomeStore.setPinned(m.id, !m.pinned) },
                        onHide = { HomeStore.setHidden(m.id, true) },
                        onLongPress = enterEdit,
                    ) {
                        when (m.id) {
                            HomeLayoutOps.PROJECTS -> ProjectsModule(
                                projects, agentName, Modifier,
                                onOpen = { projectPath = it.path },
                                onNew = { newProject = true },
                                limit = HOME_PROJECTS,
                                onAll = { Nav.selectTab(Route.Projects) },
                            )
                            HomeLayoutOps.TASKS -> TasksModule(
                                tasks, Modifier,
                                onOpen = { HomeActions.openTask(it) },
                                onMore = { taskId = it },
                                limit = HOME_TASKS,
                            )
                            else -> HomeLayoutOps.pluginIdOf(m.id)?.let { App.plugins.Card(it, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }

            if (editing) {
                EditFooter(
                    hidden = hidden,
                    titleOf = { moduleTitle(it, pluginById) },
                    onShow = { HomeStore.setHidden(it, false) },
                    onImport = importCard,
                    onDone = { editing = false },
                )
            } else if (hidden.isNotEmpty()) {
                TextLink(
                    "${hidden.size} 个模块已隐藏 · 编辑首页", { editing = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(dev.haniya.x.ui.bottomGap()))
        }

        val project: Project? = projectPath?.let { path ->
            projects.firstOrNull { it.path == path } ?: Project(path = path, name = Fmt.folderName(path))
        }
        ProjectSheet(
            project = project, tasks = tasks, start = layout.start,
            onDismiss = { projectPath = null },
            onLaunchAgent = { launch = it },
        )
        TaskSheet(task = taskId?.let { id -> tasks.firstOrNull { it.id == id } }, start = layout.start, onDismiss = { taskId = null })
        NewProjectSheet(
            visible = newProject,
            onDismiss = { newProject = false },
            onCreated = { path -> newProject = false; projectPath = path },
        )
        AgentLaunchSheet(launch) { launch = null }
    }
}

private const val HOME_PROJECTS = 3
private const val HOME_TASKS = 3

private fun moduleTitle(id: String, plugins: Map<String, PluginSummary>): String = when (id) {
    HomeLayoutOps.PROJECTS -> "最近项目"
    HomeLayoutOps.TASKS -> "进行中的任务"
    HomeLayoutOps.SERVICES -> "后台服务与日志"
    else -> HomeLayoutOps.pluginIdOf(id)?.let { pid -> plugins[pid]?.name ?: pid } ?: id
}

@Composable
private fun HomeHeader(runningTasks: Int, onlineServices: Int, editing: Boolean, onToggleEdit: () -> Unit) {
    val c = ReliefTheme.colors
    val now = rememberNow(60_000)
    val hour = remember(now) { LocalTime.now().hour }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(Fmt.greeting(hour), style = ReliefTheme.type.greeting, maxLines = 1, modifier = Modifier.weight(1f))
            if (editing) GlassButton("完成", onToggleEdit, size = ButtonSize.Small, icon = "check")
            else IconCircleButton("edit", "编辑首页", onToggleEdit)
        }
        // Full width under the greeting so it never wraps around the buttons.
        Text(
            Fmt.workspaceSummary(runningTasks, onlineServices),
            style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A module plus, in edit mode, its controls: move up / down, pin, hide. */
@Composable
private fun ModuleSlot(
    title: String,
    builtin: Boolean,
    module: dev.haniya.x.home.HomeModule,
    editing: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = ReliefTheme.colors
    Column(
        Modifier.fillMaxWidth().then(if (editing) Modifier else Modifier.longPressCapture(onLongPress)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editing) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title, style = ReliefTheme.type.label, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), translate = builtin,
                )
                RoundToggle("chevU", "上移", { onMove(-1) }, enabled = canUp, size = 40.dp)
                RoundToggle("chevD", "下移", { onMove(+1) }, enabled = canDown, size = 40.dp)
                RoundToggle("pin", if (module.pinned) "取消固定" else "固定在顶部", onPin, selected = module.pinned, size = 40.dp)
                RoundToggle("eyeOff", "隐藏", onHide, size = 40.dp)
            }
        }
        content()
    }
}

@Composable
private fun EditFooter(
    hidden: List<dev.haniya.x.home.HomeModule>,
    titleOf: (String) -> String,
    onShow: (String) -> Unit,
    onImport: () -> Unit,
    onDone: () -> Unit,
) {
    GlassCard {
        CardHeader(
            "已隐藏的模块", icon = "eyeOff",
            caption = if (hidden.isEmpty()) "没有隐藏的模块" else "${hidden.size} 个",
        )
        if (hidden.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            hidden.forEachIndexed { i, m ->
                if (i > 0) Divider()
                ItemRow(
                    title = titleOf(m.id),
                    translate = m.id in HomeLayoutOps.BUILTIN,
                    trailing = { GlassButton("显示", { onShow(m.id) }, size = ButtonSize.Small, icon = "eye") },
                )
            }
        }
    }
    GlassCard {
        CardHeader("首页卡片", icon = "puzzle", caption = "让智能体用 hx plugin new 创建卡片，或导入别人分享的 .hxcard 文件")
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("导入卡片", onImport, Modifier.weight(1f), icon = "download")
            PrimaryButton("完成", onDone, Modifier.weight(1f))
        }
    }
}
