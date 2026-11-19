package dev.haniya.x.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.component.tr
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.projects.Project
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.services.ServiceDefs
import dev.haniya.x.services.ServiceState
import dev.haniya.x.services.ServiceStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route

private const val PROJECTS_SHOWN = 5
private const val TASKS_SHOWN = 6

/** 最近项目: top projects (pinned first), tap opens the project sheet. */
@Composable
fun ProjectsModule(
    projects: List<Project>,
    agentName: (String) -> String,
    modifier: Modifier,
    onOpen: (Project) -> Unit,
    onNew: () -> Unit,
    limit: Int = PROJECTS_SHOWN,
    title: String = "最近项目",
    /** When set, overflow links to the full list instead of expanding in place. */
    onAll: (() -> Unit)? = null,
) {
    val c = ReliefTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    GlassCard(modifier) {
        CardHeader(
            title, icon = "folder",
            caption = if (projects.isEmpty()) null else "${projects.size} 个项目",
            trailing = { SmallRoundButton("plus", "新建项目", onNew) },
        )
        if (projects.isEmpty()) {
            ModuleEmpty("还没有项目", "新建一个目录，或从 Git 克隆仓库到 /root/projects", "新建项目", onNew)
        } else {
            Spacer(Modifier.height(6.dp))
            val shown = if (expanded) projects else projects.take(limit)
            shown.forEachIndexed { i, p ->
                if (i > 0) Divider()
                ItemRow(
                    title = p.name, subtitle = Fmt.shortPath(p.path), onClick = { onOpen(p) },
                    trailing = {
                        p.lastAgent?.let { Badge(agentName(it), tone = BadgeTone.Neutral) }
                        if (p.pinned) HIcon("pin", size = 16.dp, tint = c.mid, contentDescription = tr("已固定"))
                        HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.7f))
                    },
                )
            }
            if (projects.size > limit) {
                Divider()
                if (onAll != null) TextLink("全部项目", onAll, modifier = Modifier.padding(top = 6.dp), icon = "chevR")
                else TextLink(
                    if (expanded) "收起" else "显示全部 ${projects.size} 个", { expanded = !expanded },
                    modifier = Modifier.padding(top = 6.dp), icon = if (expanded) "chevU" else "chevD",
                )
            }
        }
    }
}

/** 进行中的任务: running first, then tasks from the last 24 h. */
@Composable
fun TasksModule(
    tasks: List<TaskRecord>,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onMore: (String) -> Unit,
    limit: Int = TASKS_SHOWN,
) {
    val c = ReliefTheme.colors
    val now = rememberNow(30_000)
    val list = remember(tasks, now) { TaskList.forHome(tasks, now) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val running = TaskList.runningCount(list)
    GlassCard(modifier) {
        CardHeader(
            "进行中的任务", icon = "terminal",
            caption = when {
                list.isEmpty() -> null
                running > 0 -> "$running 个运行中"
                else -> "最近 24 小时"
            },
        )
        if (list.isEmpty()) {
            ModuleEmpty("没有进行中的任务", "在终端或智能体里开始的工作会出现在这里", "打开终端") { Nav.go(Route.Terminal(null)) }
        } else {
            Spacer(Modifier.height(6.dp))
            val shown = if (expanded) list else list.take(limit)
            shown.forEachIndexed { i, t ->
                if (i > 0) Divider()
                val status = tr(TaskList.statusLabel(t))
                val time = if (t.status == TaskStatus.Running) tr(Fmt.duration(now - t.startedAt)) else tr(Fmt.ago(now - t.startedAt))
                ItemRow(
                    title = t.title,
                    subtitle = "${Fmt.folderName(t.cwd)} · $status · $time",
                    subtitleColor = if (t.status == TaskStatus.KilledBySystem) c.amberInk else null,
                    onClick = { onOpen(t.id) },
                    leading = { IconTile(if (t.kind == TaskKind.Agent) dev.haniya.x.agents.Adapters.iconOf(t.agentId) else "terminal", size = 34.dp, filled = false) },
                    trailing = { SmallRoundButton("list", "更多操作", { onMore(t.id) }) },
                )
            }
            if (list.size > limit) {
                Divider()
                TextLink(
                    if (expanded) "收起" else "显示全部 ${list.size} 个", { expanded = !expanded },
                    modifier = Modifier.padding(top = 6.dp), icon = if (expanded) "chevU" else "chevD",
                )
            }
        }
    }
}

/** 后台服务与日志: status dot, name, address; quick start/stop. */
@Composable
fun ServicesModule(services: List<ServiceState>, errors: Map<String, String>, modifier: Modifier) {
    val online = services.count { it.status == ServiceStatus.Running }
    GlassCard(modifier) {
        CardHeader(
            "后台服务与日志", icon = "server",
            caption = if (services.isEmpty()) null else "$online / ${services.size} 在线",
        )
        if (services.isEmpty()) {
            ModuleEmpty("还没有后台服务", "智能体可以用 hx service 登记开发服务器，这里会显示状态、地址和日志", "打开终端") {
                Nav.go(Route.Terminal(null))
            }
        } else {
            Spacer(Modifier.height(6.dp))
            services.forEachIndexed { i, s ->
                if (i > 0) Divider()
                ServiceRow(s, errors[s.def.name])
            }
        }
    }
}

@Composable
private fun ServiceRow(s: ServiceState, error: String?) {
    val c = ReliefTheme.colors
    val name = s.def.name
    val dot = when (s.status) {
        ServiceStatus.Running -> c.accent
        ServiceStatus.Starting -> c.acc2
        ServiceStatus.Unhealthy, ServiceStatus.Crashed -> c.amber
        ServiceStatus.Stopped -> c.ink2
    }
    val label = tr(ServiceDefs.statusLabel(s.status, s.managed, s.lastExitCode))
    val sub = error?.let { tr(it) } ?: listOfNotNull(label, s.address).joinToString(" · ")
    ItemRow(
        title = name, subtitle = sub,
        subtitleColor = if (error != null) c.amberInk else null,
        onClick = { Nav.go(Route.Service(name)) },
        leading = { Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { StatusDot(dot) } },
        trailing = {
            val alive = s.managed && s.status != ServiceStatus.Stopped && s.status != ServiceStatus.Crashed
            when {
                alive -> RoundToggle("stop", tr("停止") + " " + name, { App.services.stop(name) })
                s.status == ServiceStatus.Running || s.status == ServiceStatus.Unhealthy -> Badge("外部", tone = BadgeTone.Neutral)
                else -> RoundToggle("play", tr("启动") + " " + name, { App.services.start(name) })
            }
        },
    )
}
