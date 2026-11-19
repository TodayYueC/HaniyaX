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
        val aid = HomeLayoutOps.agentIdOf(id)
        when {
            pid != null -> pid in pluginById
            aid != null -> dev.haniya.x.agents.Adapters.find(aid) != null
            else -> HomeLayoutOps.isKnown(id)
        }
    }
    val onHome = available
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
                        builtin = m.id in HomeLayoutOps.BUILTIN || (m.id in HomeLayoutOps.SIMPLE_CARDS && m.id != HomeLayoutOps.DSH),
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
                            HomeLayoutOps.PROJECTS -> RecentProjects(
                                projects, editing,
                                onOpen = { projectPath = it.path },
                                onNew = { newProject = true },
                            )
                            HomeLayoutOps.TASKS -> RunningTasks(tasks, editing)
                            HomeLayoutOps.SERVICES -> ServicesModule(services, serviceErrors, Modifier)
                            HomeLayoutOps.TERMINAL -> TerminalHomeCard(tasks)
                            HomeLayoutOps.DSH -> DshHomeCard()
                            HomeLayoutOps.FILES -> ShortcutHomeCard("folder", "文件", "浏览工作区与手机存储", true) { Nav.go(Route.Files()) }
                            else -> {
                                HomeLayoutOps.agentIdOf(m.id)?.let { AgentHomeCard(it, tasks, onLaunch = { r -> launch = r }) }
                                HomeLayoutOps.projectPathOf(m.id)?.let { ProjectHomeCard(it, projects, onOpen = { p -> projectPath = p }, onLaunch = { r -> launch = r }) }
                                HomeLayoutOps.pluginIdOf(m.id)?.let { App.plugins.Card(it, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }

            if (editing) {
                AddCardsPanel(
                    groups = cardOptions(layout, projects, pluginById),
                    onAdd = { HomeStore.add(it) },
                    onImport = importCard,
                    onDone = { editing = false },
                )
            } else {
                TextLink("编辑首页", { editing = true }, modifier = Modifier.align(Alignment.CenterHorizontally), icon = "plus")
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
    HomeLayoutOps.TASKS -> "正在运行"
    HomeLayoutOps.SERVICES -> "后台服务"
    HomeLayoutOps.TERMINAL -> "终端"
    HomeLayoutOps.DSH -> "DeepSeek Harness"
    HomeLayoutOps.FILES -> "文件"
    else -> HomeLayoutOps.pluginIdOf(id)?.let { pid -> plugins[pid]?.name ?: pid }
        ?: HomeLayoutOps.agentIdOf(id)?.let { dev.haniya.x.agents.Adapters.find(it)?.info?.name ?: it }
        ?: HomeLayoutOps.projectPathOf(id)?.let { Fmt.folderName(it) }
        ?: id
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


/** 正在运行: only live tasks, as quiet rows; the whole block is absent when nothing runs. */
@Composable
private fun RunningTasks(tasks: List<dev.haniya.x.runtime.TaskRecord>, editing: Boolean) {
    val c = ReliefTheme.colors
    val now = rememberNow(30_000)
    val running = remember(tasks) { tasks.filter { it.status == TaskStatus.Running }.sortedByDescending { it.startedAt } }
    if (running.isEmpty()) {
        if (editing) Text("没有进行中的任务", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!editing) SectionLabel("正在运行")
        GlassCard {
            running.forEachIndexed { i, t ->
                if (i > 0) Divider()
                ItemRow(
                    title = t.title,
                    subtitle = "${Fmt.folderName(t.cwd)} · ${dev.haniya.relief.component.tr(Fmt.duration(now - t.startedAt))}",
                    onClick = { HomeActions.openTask(t.id) },
                    leading = { Box(Modifier.padding(horizontal = 4.dp)) { StatusDot(c.accent, size = 8.dp) } },
                    trailing = { dev.haniya.relief.icon.HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.6f)) },
                )
            }
        }
    }
}

/** 最近项目: three plain rows and a link to the 项目 tab. */
@Composable
private fun RecentProjects(projects: List<Project>, editing: Boolean, onOpen: (Project) -> Unit, onNew: () -> Unit) {
    val c = ReliefTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!editing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("最近项目", Modifier.weight(1f))
            if (projects.size > HOME_PROJECTS) TextLink("全部", { Nav.selectTab(Route.Projects) }, modifier = Modifier.padding(end = 4.dp, top = 6.dp))
        }
        GlassCard {
            if (projects.isEmpty()) {
                ItemRow(title = "新建项目", subtitle = "在 ~/projects 下创建一个空文件夹", translate = true, onClick = onNew,
                    trailing = { dev.haniya.relief.icon.HIcon("plus", size = 18.dp, tint = c.ink2) })
            } else projects.take(HOME_PROJECTS).forEachIndexed { i, p ->
                if (i > 0) Divider()
                ItemRow(
                    title = p.name, subtitle = Fmt.shortPath(p.path), onClick = { onOpen(p) },
                    trailing = { dev.haniya.relief.icon.HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.6f)) },
                )
            }
        }
    }
}
