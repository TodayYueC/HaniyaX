package dev.haniya.x.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.agents.AgentStatus
import dev.haniya.x.agents.AgentUiLogic
import dev.haniya.x.agents.Adapters
import dev.haniya.x.agents.LaunchRequest
import dev.haniya.x.core.Paths
import dev.haniya.x.plugins.PluginSummary
import dev.haniya.x.projects.Project
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.services.ServiceStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route

/** One entry of the 添加卡片 list. */
data class CardOption(val id: String, val title: String, val subtitle: String, val icon: String, val translate: Boolean)

/** Everything that can be added to Home and is not showing yet: built-in cards, agents, projects, hidden modules. */
fun cardOptions(
    layout: HomeLayout,
    projects: List<Project>,
    plugins: Map<String, PluginSummary>,
): List<Pair<String, List<CardOption>>> {
    val showing = layout.modules.filter { !it.hidden }.mapTo(HashSet()) { it.id }
    fun free(id: String) = id !in showing
    val general = buildList {
        if (free(HomeLayoutOps.TASKS)) add(CardOption(HomeLayoutOps.TASKS, "正在运行", "正在运行的终端与智能体", "terminal", true))
        if (free(HomeLayoutOps.PROJECTS)) add(CardOption(HomeLayoutOps.PROJECTS, "最近项目", "最近打开的三个项目", "folder", true))
        if (free(HomeLayoutOps.TERMINAL)) add(CardOption(HomeLayoutOps.TERMINAL, "终端", "会话数量，一键新建 Shell", "terminal", true))
        if (free(HomeLayoutOps.DSH)) add(CardOption(HomeLayoutOps.DSH, "DeepSeek Harness", "打开内嵌工作页", "whale", false))
        if (free(HomeLayoutOps.FILES)) add(CardOption(HomeLayoutOps.FILES, "文件", "浏览工作区与手机存储", "folder", true))
        if (free(HomeLayoutOps.SERVICES)) add(CardOption(HomeLayoutOps.SERVICES, "后台服务", "状态、地址，一键启停", "server", true))
    }
    val agents = Adapters.all.map { it.info }.filter { free(HomeLayoutOps.agentModuleId(it.id)) }
        .map { CardOption(HomeLayoutOps.agentModuleId(it.id), it.name, it.vendor, it.icon.ifBlank { "bot" }, false) }
    val projectCards = projects.filter { free(HomeLayoutOps.projectModuleId(it.path)) }.take(8)
        .map { CardOption(HomeLayoutOps.projectModuleId(it.path), it.name, Fmt.shortPath(it.path), "folder", false) }
    val pluginCards = layout.modules.filter { it.hidden && HomeLayoutOps.pluginIdOf(it.id) in plugins }
        .map { m -> val p = plugins.getValue(HomeLayoutOps.pluginIdOf(m.id)!!); CardOption(m.id, p.name, "插件卡片", "puzzle", false) }
    return listOf("常用" to general, "智能体" to agents, "项目" to projectCards, "插件卡片" to pluginCards).filter { it.second.isNotEmpty() }
}

/** Card for one agent: status, start, and a way back into its running session. */
@Composable
fun AgentHomeCard(agentId: String, tasks: List<TaskRecord>, onLaunch: (LaunchRequest) -> Unit) {
    val c = ReliefTheme.colors
    val a = Adapters.find(agentId) ?: return
    val status by App.agents.status.collectAsState()
    val s = status[agentId]
    val running = tasks.filter { it.agentId == agentId && it.status == TaskStatus.Running }.maxByOrNull { it.startedAt }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(a.info.icon.ifBlank { "bot" }, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(a.info.name, style = ReliefTheme.type.title, maxLines = 1, translate = false)
                Text(
                    if (running != null) "${Fmt.folderName(running.cwd)} · 运行中" else AgentUiLogic.statusLine(s),
                    style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                s is AgentStatus.Installed -> {
                    PrimaryButton("启动", { onLaunch(LaunchRequest(agentId = agentId)) }, size = ButtonSize.Small, icon = "play")
                    if (running != null) GlassButton("回到会话", { HomeActions.openTask(running.id) }, size = ButtonSize.Small)
                }
                s == AgentStatus.NotInstalled || s is AgentStatus.Error ->
                    GlassButton("去安装", { Nav.selectTab(Route.Agents) }, size = ButtonSize.Small, icon = "download")
                else -> GlassButton("智能体", { Nav.selectTab(Route.Agents) }, size = ButtonSize.Small)
            }
        }
    }
}

/** Card for one project: open a shell or start an agent there. */
@Composable
fun ProjectHomeCard(path: String, projects: List<Project>, onOpen: (String) -> Unit, onLaunch: (LaunchRequest) -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val p = projects.firstOrNull { it.path == path }
    GlassCard(onClick = { onOpen(path) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile("folder", size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(p?.name ?: Fmt.folderName(path), style = ReliefTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                Text(Fmt.shortPath(path), style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("终端", { HomeActions.openShell(path)?.let { toast.show(it, icon = "warn") } }, size = ButtonSize.Small, icon = "terminal")
            GlassButton("智能体", { onLaunch(LaunchRequest(projectPath = path, agentId = p?.lastAgent)) }, size = ButtonSize.Small, icon = "bot")
        }
    }
}

/** 终端: session count and a new shell in ~. */
@Composable
fun TerminalHomeCard(tasks: List<TaskRecord>) {
    val toast = LocalToast.current
    val shells = tasks.count { it.status == TaskStatus.Running && it.kind == TaskKind.Shell }
    GlassCard(onClick = { Nav.go(Route.Terminal(null)) }) {
        CardHeader("终端", icon = "terminal", caption = if (shells > 0) "$shells 个会话" else "没有运行中的会话")
        Spacer(Modifier.height(14.dp))
        GlassButton("新建 Shell", { HomeActions.openShell(Paths.GUEST_HOME)?.let { toast.show(it, icon = "warn") } }, size = ButtonSize.Small, icon = "plus")
    }
}

/** A single tappable row card (DeepSeek Harness, 文件). */
@Composable
fun ShortcutHomeCard(icon: String, title: String, subtitle: String, translateTitle: Boolean, onClick: () -> Unit) {
    val c = ReliefTheme.colors
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = ReliefTheme.type.title, maxLines = 1, translate = translateTitle)
                Text(subtitle, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1)
            }
            HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun DshHomeCard() {
    val services by App.services.services.collectAsState()
    val online = services.any { (it.def.owner == "dsh" || it.def.name == "dsh") && it.status == ServiceStatus.Running }
    ShortcutHomeCard("whale", "DeepSeek Harness", if (online) "服务在线" else "DeepSeek 的开源智能体工作台", false) { Nav.go(Route.Dsh) }
}

/** The 添加卡片 list shown in edit mode. */
@Composable
fun AddCardsPanel(groups: List<Pair<String, List<CardOption>>>, onAdd: (String) -> Unit, onImport: () -> Unit, onDone: () -> Unit) {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("添加卡片", icon = "plus", caption = "点「添加」放到首页，可在上方调整顺序")
        groups.forEach { (group, options) ->
            Spacer(Modifier.height(12.dp))
            Text(group, style = ReliefTheme.type.label, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
            options.forEachIndexed { i, o ->
                if (i > 0) Divider()
                ItemRow(
                    title = o.title, subtitle = o.subtitle, translate = o.translate,
                    leading = { IconTile(o.icon, size = 34.dp, filled = false) },
                    trailing = { GlassButton("添加", { onAdd(o.id) }, size = ButtonSize.Small, icon = "plus") },
                )
            }
        }
        if (groups.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("所有卡片都已在首页", style = ReliefTheme.type.caption, color = c.ink2)
        }
    }
    GlassCard {
        CardHeader("插件卡片", icon = "puzzle", caption = "让智能体用 hx plugin new 创建卡片，或导入别人分享的 .hxcard 文件")
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("导入卡片", onImport, Modifier.weight(1f), icon = "download")
            PrimaryButton("完成", onDone, Modifier.weight(1f))
        }
    }
}
