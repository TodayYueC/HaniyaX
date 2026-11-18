package dev.haniya.x.providers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.FieldLabel
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.HChip
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.SegmentedControl
import dev.haniya.relief.component.SectionTitle
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Provider / model management per tool (Claude Code, Codex, OpenCode) plus a pointer to DSH's own settings. */
@Composable
fun ProvidersScreen(agentId: String?) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val tabs = Tool.entries.map { it.label } + "DSH"
    var tab by remember { mutableIntStateOf(Tool.entries.indexOfFirst { it.id == agentId }.let { if (agentId == "dsh") Tool.entries.size else if (it < 0) 0 else it }) }
    val tool = Tool.entries.getOrNull(tab)
    val store by ProviderStore.file.collectAsState()
    var effective by remember { mutableStateOf<EffectiveConfig?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Provider?>(null) }
    var switched by remember { mutableStateOf<Tool?>(null) }
    var restoreFor by remember { mutableStateOf<Tool?>(null) }

    LaunchedEffect(tool, refreshTick) {
        ProviderStore.load()
        effective = tool?.let { runCatching { ProviderStore.effective(it) }.getOrNull() }
    }

    fun use(t: Tool, p: Provider) {
        scope.launch {
            runCatching { ProviderStore.switchTo(t, p) }
                .onSuccess { switched = t; refreshTick++ }
                .onFailure { toast.show(it.message ?: "切换失败", icon = "info") }
        }
    }

    Page(
        "服务商与模型",
        caption = "只修改连接、认证和模型字段，其余设置保持不变",
        overlay = {
            ProviderEditSheet(
                provider = editing,
                tool = tool,
                onDismiss = { editing = null },
                onSave = { p, andUse ->
                    scope.launch {
                        val saved = ProviderStore.upsert(p)
                        editing = null
                        if (andUse && tool != null) use(tool, saved) else refreshTick++
                    }
                },
                onDelete = { p -> scope.launch { ProviderStore.delete(p.id); editing = null; refreshTick++ } },
            )
            EffectScopeSheet(tool = switched, onDismiss = { switched = null })
            RestoreSheet(tool = restoreFor, onDismiss = { restoreFor = null }, onRestored = { refreshTick++; toast.show("已恢复上一份配置") })
        },
    ) {
        SegmentedControl(tabs, tab, { tab = it }, modifier = Modifier.fillMaxWidth())
        if (tool == null) {
            DshProviders()
            return@Page
        }
        val eff = effective
        GlassCard {
            CardHeader("当前生效", icon = "zap", caption = eff?.source ?: "读取中…")
            if (eff != null) {
                Spacer(Modifier.height(8.dp))
                ListRow("服务地址", value = eff.baseUrl ?: "官方默认", chevron = false)
                Divider()
                ListRow("模型", value = eff.model ?: "工具默认", chevron = false)
                Divider()
                ListRow("认证", value = eff.auth, chevron = false)
                if (eff.overrides.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("注意：以下环境变量会覆盖配置文件（对新打开的终端生效）：", style = ReliefTheme.type.caption, color = c.amberInk)
                    for (o in eff.overrides.take(6)) Text(o, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
                }
            }
        }

        val mine = store.providers.filter { it.tool == tool.id }
        val currentId = store.current[tool.id]
        SectionTitle("官方账号")
        val official = Presets.official(tool)
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(official.name, style = ReliefTheme.type.h3)
                    Text(loginHint(tool), style = ReliefTheme.type.caption, color = c.ink2)
                }
                if (currentId == official.id) Badge("当前")
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentId != official.id) PrimaryButton("使用官方账号", size = ButtonSize.Small, onClick = { use(tool, official) })
                GlassButton("在终端中登录", size = ButtonSize.Small, icon = "terminal", onClick = {
                    val (title, cmd) = loginCommand(tool)
                    val id = Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = title, cwd = "/root", command = cmd))
                    Nav.go(Route.Terminal(id))
                })
            }
        }

        SectionTitle("自定义 API", action = { TextLink("添加", onClick = { editing = Provider(id = "", tool = tool.id, name = "") }) })
        if (mine.isEmpty()) {
            GlassCard {
                Text("还没有自定义服务商", style = ReliefTheme.type.h3)
                Text("可以从 DeepSeek、Kimi、智谱等预设开始，填入 API Key 即可。", style = ReliefTheme.type.caption, color = c.ink2)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("添加服务商", size = ButtonSize.Small, icon = "plus", onClick = { editing = Provider(id = "", tool = tool.id, name = "") })
            }
        }
        for (p in mine.sortedBy { it.createdAt }) {
            GlassCard(onClick = { editing = p }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = ReliefTheme.type.h3, translate = false)
                        Text(p.baseUrl, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, translate = false)
                        Text("${p.model.ifBlank { "默认模型" }} · ${Masks.key(p.apiKey)}", style = ReliefTheme.type.caption, color = c.ink2, translate = false)
                    }
                    if (currentId == p.id) Badge("当前") else GlassButton("使用", size = ButtonSize.Small, onClick = { use(tool, p) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            TextLink("恢复上一份可用配置", onClick = { restoreFor = tool })
        }
        Text(
            "配置在工作区内（~/.haniyax/providers.json，仅自己可读），不会离开你的设备。服务商预设来自开源项目 CC Switch。",
            style = ReliefTheme.type.caption, color = c.ink2,
        )
    }
}

private fun loginHint(t: Tool) = when (t) {
    Tool.Claude -> "使用 Claude 订阅或 Console 账号。登录在 Claude Code 内完成（/login）。"
    Tool.Codex -> "使用 ChatGPT 账号。登录时会打开浏览器，完成后自动回到 Codex。"
    Tool.OpenCode -> "使用 OpenCode 自带的登录（opencode auth login）。"
}

private fun loginCommand(t: Tool): Pair<String, String> = when (t) {
    Tool.Claude -> "Claude 登录" to "echo '在 Claude Code 中输入 /login 完成登录'; claude"
    Tool.Codex -> "Codex 登录" to "codex login"
    Tool.OpenCode -> "OpenCode 登录" to "opencode auth login"
}

@Composable
private fun DshProviders() {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("DeepSeek Harness", icon = "spark", caption = "服务商在 DeepSeek Harness 的设置页中管理")
        Spacer(Modifier.height(8.dp))
        Text(
            "DeepSeek Harness 自带完整的服务商与模型设置（设置 → 模型），并会校验配置版本。为避免两处同时改写同一份配置，HaniyaX 不直接修改它。修改后无需重启，新会话即生效。",
            style = ReliefTheme.type.body, color = c.ink2,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("打开 DeepSeek Harness", size = ButtonSize.Small, icon = "arrow", onClick = { Nav.go(Route.Dsh) })
    }
}

@Composable
private fun ProviderEditSheet(
    provider: Provider?,
    tool: Tool?,
    onDismiss: () -> Unit,
    onSave: (Provider, Boolean) -> Unit,
    onDelete: (Provider) -> Unit,
) {
    val c = ReliefTheme.colors
    GlassSheet(visible = provider != null && tool != null, onDismiss = onDismiss, title = "服务商") {
        val p0 = provider ?: return@GlassSheet
        val t = tool ?: return@GlassSheet
        var name by remember(p0) { mutableStateOf(p0.name) }
        var baseUrl by remember(p0) { mutableStateOf(p0.baseUrl) }
        var apiKey by remember(p0) { mutableStateOf(p0.apiKey) }
        var model by remember(p0) { mutableStateOf(p0.model) }
        var small by remember(p0) { mutableStateOf(p0.smallModel) }
        var claudeAuth by remember(p0) { mutableStateOf(p0.claudeAuth) }
        var wire by remember(p0) { mutableStateOf(p0.wireApi) }
        var npm by remember(p0) { mutableStateOf(p0.npm) }
        var showKey by remember(p0) { mutableStateOf(false) }
        var confirmDelete by remember(p0) { mutableStateOf(false) }
        val isNew = p0.id.isBlank()

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(if (isNew) "添加服务商 · ${t.label}" else "编辑服务商", style = ReliefTheme.type.title)
            if (isNew) {
                FieldLabel("从预设开始")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pr in Presets.forTool(t)) HChip(pr.name, selected = name == pr.name && baseUrl == pr.baseUrl, onClick = {
                        name = pr.name; baseUrl = pr.baseUrl; model = pr.model; small = pr.smallModel; wire = pr.wireApi; npm = pr.npm
                    })
                }
            }
            FieldLabel("名称")
            GlassField(name, { name = it }, placeholder = "例如 DeepSeek")
            FieldLabel("API 地址")
            GlassField(baseUrl, { baseUrl = it.trim() }, placeholder = "https://…")
            FieldLabel("API Key")
            GlassField(
                apiKey, { apiKey = it.trim() }, placeholder = "sk-…",
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { SmallRoundButton(if (showKey) "eyeOff" else "eye", contentDescription = if (showKey) "隐藏" else "显示", onClick = { showKey = !showKey }) },
            )
            FieldLabel("模型")
            GlassField(model, { model = it.trim() }, placeholder = "模型名称")
            when (t) {
                Tool.Claude -> {
                    FieldLabel("快速模型（可选，用于后台小任务）")
                    GlassField(small, { small = it.trim() }, placeholder = "留空则与主模型相同")
                    FieldLabel("认证方式")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HChip("Auth Token", selected = claudeAuth == ClaudeAuth.AuthToken, onClick = { claudeAuth = ClaudeAuth.AuthToken })
                        HChip("API Key", selected = claudeAuth == ClaudeAuth.ApiKey, onClick = { claudeAuth = ClaudeAuth.ApiKey })
                    }
                    Text("多数第三方服务使用 Auth Token（ANTHROPIC_AUTH_TOKEN）。", style = ReliefTheme.type.caption, color = c.ink2)
                }
                Tool.Codex -> {
                    FieldLabel("接口协议")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HChip("Responses", selected = wire == "responses", onClick = { wire = "responses" })
                        HChip("Chat Completions", selected = wire == "chat", onClick = { wire = "chat" })
                    }
                    Text("Key 写入 ~/.codex/auth.json；切回官方账号时会恢复原来的 ChatGPT 登录。", style = ReliefTheme.type.caption, color = c.ink2)
                }
                Tool.OpenCode -> {
                    FieldLabel("接口类型")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HChip("OpenAI 兼容", selected = npm == "@ai-sdk/openai-compatible", onClick = { npm = "@ai-sdk/openai-compatible" })
                        HChip("Anthropic 兼容", selected = npm == "@ai-sdk/anthropic", onClick = { npm = "@ai-sdk/anthropic" })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            val valid = name.isNotBlank() && baseUrl.startsWith("http")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val built = p0.copy(name = name.trim(), baseUrl = baseUrl, apiKey = apiKey, model = model, smallModel = small, claudeAuth = claudeAuth, wireApi = wire, npm = npm, tool = t.id)
                PrimaryButton("保存并使用", enabled = valid, onClick = { onSave(built, true) })
                GlassButton("仅保存", enabled = valid, onClick = { onSave(built, false) })
            }
            if (!isNew) {
                Spacer(Modifier.height(16.dp))
                if (!confirmDelete) TextLink("删除此服务商", onClick = { confirmDelete = true })
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("删除后无法恢复（已写入的配置不受影响）", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f))
                    GlassButton("确认删除", size = ButtonSize.Small, contentColor = c.danger, onClick = { onDelete(p0) })
                }
            }
        }
    }
}

@Composable
private fun EffectScopeSheet(tool: Tool?, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val tasks by Runtime.tasks.tasks.collectAsState()
    GlassSheet(visible = tool != null, onDismiss = onDismiss, title = "已切换") {
        val t = tool ?: return@GlassSheet
        val running = tasks.filter { it.agentId == t.id && it.status == TaskStatus.Running }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("已切换 ${t.label} 的服务商", style = ReliefTheme.type.title)
            Text("新打开的 ${t.label} 会话会立即使用新配置。", style = ReliefTheme.type.body, color = c.ink2)
            if (running.isNotEmpty()) {
                Text("以下 ${running.size} 个正在运行的会话仍使用启动时的配置，需要重新打开才会生效：", style = ReliefTheme.type.body, color = c.ink2)
                for (r in running) ListRow(r.title, icon = "bot", subtitle = r.cwd, onClick = {
                    onDismiss(); Runtime.tasks.openTab(r.id); Runtime.tasks.focus(r.id); Nav.go(Route.Terminal(r.id))
                })
            }
            GlassButton("好的", onClick = onDismiss)
        }
    }
}

@Composable
private fun RestoreSheet(tool: Tool?, onDismiss: () -> Unit, onRestored: () -> Unit) {
    val c = ReliefTheme.colors
    val scope = rememberCoroutineScope()
    GlassSheet(visible = tool != null, onDismiss = onDismiss, title = "恢复配置") {
        val t = tool ?: return@GlassSheet
        var snaps by remember(t) { mutableStateOf<List<Snapshot>?>(null) }
        LaunchedEffect(t) { snaps = ProviderStore.snapshots(t) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("恢复上一份可用配置", style = ReliefTheme.type.title)
            val s = snaps
            when {
                s == null -> Text("读取中…", style = ReliefTheme.type.body, color = c.ink2)
                s.isEmpty() -> Text("还没有可恢复的记录。每次切换服务商前，HaniyaX 都会自动保存一份。", style = ReliefTheme.type.body, color = c.ink2)
                else -> {
                    val latest = s.first()
                    Text(
                        "将恢复到 ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(latest.meta.createdAt))} 切换前的状态，涉及：" +
                            latest.meta.files.keys.joinToString("、"),
                        style = ReliefTheme.type.body, color = c.ink2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("恢复", onClick = { scope.launch { ProviderStore.restoreLatest(t); onDismiss(); onRestored() } })
                        GlassButton("取消", onClick = onDismiss)
                    }
                }
            }
        }
    }
}
