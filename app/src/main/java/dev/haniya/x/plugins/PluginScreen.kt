package dev.haniya.x.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.EmptyState
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.LiquidSwitch
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Route.Plugin: details, trust & approval, params, live preview, export and removal of one card. */
@Composable
fun PluginScreen(pluginId: String) {
    val host = PluginHost.instance
    val loaded by host.loaded.collectAsState()
    val ready by host.firstLoadDone.collectAsState()
    val lp = loaded[pluginId]
    val toast = LocalToast.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val dir = lp?.dir ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
                    out.use { HxCard.export(dir, it) }
                }
            }
            r.fold(
                { toast.show("已导出卡片，本机参数未包含在内") },
                { toast.show("导出失败：${it.message ?: "未知原因"}", icon = "info") },
            )
        }
    }

    Page(
        title = lp?.name ?: pluginId,
        caption = lp?.let { "${it.id} · v${it.version}" },
        overlay = {
            GlassSheet(visible = confirmRemove, onDismiss = { if (!removing) confirmRemove = false }, title = "移除卡片") {
                val c = ReliefTheme.colors
                Text(
                    "将删除卡片目录 ~/.haniyax/plugins/$pluginId（包括本机参数）以及它的命令确认记录。此操作无法撤销。",
                    style = ReliefTheme.type.body, color = c.ink, translate = false,
                )
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("取消", onClick = { confirmRemove = false }, modifier = Modifier.weight(1f), enabled = !removing)
                    PrimaryButton(
                        "移除", color = c.danger, enabled = !removing, modifier = Modifier.weight(1f), icon = "trash",
                        onClick = {
                            removing = true
                            scope.launch {
                                val ok = host.removeNow(pluginId)
                                removing = false
                                confirmRemove = false
                                if (ok) { toast.show("已移除卡片"); Nav.back() } else toast.show("没能完全删除卡片目录，请稍后再试", icon = "info")
                            }
                        },
                    )
                }
            }
        },
    ) {
        if (lp == null) {
            GlassCard {
                EmptyState(
                    if (ready) "找不到这张卡片" else "正在读取卡片…",
                    subtitle = if (ready) "它可能已被移除。卡片位于 ~/.haniyax/plugins/$pluginId" else null,
                    icon = "puzzle",
                )
            }
            return@Page
        }
        val m = lp.manifest
        SummaryCard(lp)
        TrustCard(host, lp)
        if (lp.errors.isNotEmpty() || lp.warnings.isNotEmpty()) IssuesCard(lp)
        if (m != null && m.params.isNotEmpty()) ParamsCard(host, lp, m)
        if (m != null) {
            Text("预览", style = ReliefTheme.type.title, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            PluginCard(host, pluginId, Modifier)
        }
        ManageCard(
            lp,
            onExport = { exporter.launch("${lp.id}.hxcard") },
            onTerminal = {
                runCatching {
                    val tid = Runtime.tasks.start(TaskSpec(TaskKind.Shell, title = "卡片 ${lp.id}", cwd = lp.guestDir))
                    Nav.go(Route.Terminal(tid))
                }.onFailure { toast.show("无法打开终端：${it.message ?: "未知原因"}", icon = "info") }
            },
            onRemove = { confirmRemove = true },
        )
    }
}

@Composable
private fun SummaryCard(lp: LoadedPlugin) {
    val c = ReliefTheme.colors
    val m = lp.manifest
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(lp.icon, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(lp.name, style = ReliefTheme.type.title, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
                Text("${lp.id} · v${lp.version}", style = ReliefTheme.type.caption, color = c.ink2, translate = false)
            }
            when {
                m == null -> Badge("未启用", tone = BadgeTone.Neutral)
                lp.trust == PluginTrust.RunsCommands -> CommandBadge()
                else -> Badge("仅展示", tone = BadgeTone.Neutral)
            }
        }
        m?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = ReliefTheme.type.body, color = c.ink, modifier = Modifier.padding(top = 12.dp), translate = false)
        }
        Text(lp.guestDir, style = monoStyle, color = c.ink2, modifier = Modifier.padding(top = 10.dp), translate = false)
    }
}

/** Human label of where a command lives in the manifest. */
private fun commandWhere(m: Manifest, e: CommandEntry): String {
    val name = e.where.substringAfter('.')
    return if (e.where.startsWith("sources.")) "数据源 $name · 每 ${m.refreshSec} 秒运行一次"
    else {
        val a = m.action(name)
        val label = a?.label ?: name
        when (e.kind) {
            "terminal" -> "动作「$label」· 在终端中运行"
            "service" -> "动作「$label」· 操作后台服务"
            else -> if ((a as? ActionDef.Command)?.confirm == true) "动作「$label」· 点按后再确认一次" else "动作「$label」· 点按时运行"
        }
    }
}

private fun sourceLine(s: SourceDef, ctx: TemplateContext): Pair<String, String> = when (s) {
    is SourceDef.Port -> "plug" to "端口 ${s.host}:${Templates.render(s.port, ctx).trim()}"
    is SourceDef.Http -> "globe" to "本机地址 ${Templates.render(s.url, ctx).trim()}"
    is SourceDef.FileSrc -> "note" to "文件 ${ParamValues.expandHome(Templates.render(s.path, ctx).trim())}"
    is SourceDef.Process -> "cpu" to "进程 匹配「${Templates.render(s.match, ctx)}」"
    is SourceDef.Command -> "terminal" to "命令 ${s.name}"
}

@Composable
private fun TrustCard(host: PluginHost, lp: LoadedPlugin) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val m = lp.manifest ?: return
    val ctx = lp.templateContext()
    val readers = m.sources.values.filter { it !is SourceDef.Command }
    GlassCard {
        if (lp.trust == PluginTrust.DisplayOnly) {
            CardHeader("仅展示", icon = "eye", caption = "只读取文件、端口与本机地址，不会运行命令")
            if (readers.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) { readers.forEach { SourceRow(sourceLine(it, ctx)) } }
            return@GlassCard
        }
        CardHeader("可执行命令", icon = "terminal", caption = "会在工作区中运行以下命令") {
            when (lp.approval) {
                ApprovalState.Approved -> Badge("已允许", tone = BadgeTone.Accent)
                ApprovalState.Changed -> Badge("需重新确认", tone = BadgeTone.Amber)
                else -> Badge("未允许", tone = BadgeTone.Amber)
            }
        }
        val stateText = when (lp.approval) {
            ApprovalState.Approved -> "你已允许这些命令。命令或其中的参数一旦变化，需要重新确认。"
            ApprovalState.Changed -> "命令已变更，需要重新确认。确认前，卡片不会运行任何命令。"
            else -> "确认前，卡片不会运行任何命令；只读的内容仍会照常显示。"
        }
        Text(stateText, style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            lp.commands.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(commandWhere(m, e), style = ReliefTheme.type.label, color = c.ink, translate = false)
                    CommandText(e)
                }
            }
        }
        if (readers.isNotEmpty()) {
            Text("同时读取", style = ReliefTheme.type.label, color = c.ink2, modifier = Modifier.padding(top = 14.dp))
            Column(Modifier.padding(top = 4.dp)) { readers.forEach { SourceRow(sourceLine(it, ctx)) } }
        }
        if (lp.params.missing.isNotEmpty()) {
            Text("请先填写参数，再确认命令。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            "确认只保存在本机的 HaniyaX 中，Agent 无法代你允许。",
            style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lp.approval == ApprovalState.Approved) {
                GlassButton("撤销允许", onClick = { host.revoke(lp.id); toast.show("已撤销，卡片不会再运行命令") }, modifier = Modifier.weight(1f), icon = "lock")
            } else {
                val hash = lp.hash
                PrimaryButton(
                    "允许运行这些命令", icon = "shield", modifier = Modifier.weight(1f),
                    enabled = hash != null && lp.params.missing.isEmpty(),
                    onClick = {
                        if (hash == null) return@PrimaryButton
                        scope.launch {
                            val ok = host.approveExact(lp.id, hash)
                            toast.show(if (ok) "已允许，卡片开始运行" else "命令刚刚有变化，请重新查看", icon = if (ok) "check" else "info")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(line: Pair<String, String>) {
    val c = ReliefTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HIcon(line.first, size = 18.dp, brush = c.brush)
        Text(line.second, style = ReliefTheme.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
    }
}

@Composable
private fun IssuesCard(lp: LoadedPlugin) {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader(
            if (lp.errors.isNotEmpty()) "需要修改的地方" else "提示", icon = if (lp.errors.isNotEmpty()) "warn" else "info",
            caption = if (lp.errors.isNotEmpty()) "修正 plugin.json 后会自动重新读取" else null,
        )
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lp.errors.forEach { Text("· $it", style = monoStyle, color = c.ink, translate = false) }
            lp.warnings.forEach { Text("· $it", style = monoStyle, color = c.ink2, translate = false) }
        }
    }
}

@Composable
private fun ParamsCard(host: PluginHost, lp: LoadedPlugin, m: Manifest) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val text = remember(lp.id) {
        mutableStateMapOf<String, String>().apply {
            for ((k, def) in m.params) {
                val v = lp.localValues[k]?.let { ParamValues.coerce(it, def.type) }
                put(k, if (v == null) "" else if (def.type == ParamType.BOOLEAN) (ParamValues.bool(v) ?: false).toString() else v.content)
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    GlassCard {
        CardHeader("参数", icon = "sliders", caption = "只保存在本机（local.json），导出和分享时不会包含")
        m.params.values.forEachIndexed { i, def ->
            if (i > 0) Divider(Modifier.padding(top = 14.dp))
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (def.type == ParamType.BOOLEAN) {
                    val checked = (text[def.name]?.takeIf { it.isNotEmpty() } ?: def.default?.let { ParamValues.bool(it)?.toString() } ?: "false") == "true"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(def.label, style = ReliefTheme.type.bodyMedium, translate = false)
                            def.description?.let { Text(it, style = ReliefTheme.type.caption, color = c.ink2, translate = false) }
                        }
                        LiquidSwitch(checked, { text[def.name] = it.toString() }, contentDescription = def.label)
                    }
                } else {
                    Text(def.label, style = ReliefTheme.type.bodyMedium, translate = false)
                    def.description?.let { Text(it, style = ReliefTheme.type.caption, color = c.ink2, translate = false) }
                    GlassField(
                        value = text[def.name] ?: "",
                        onValueChange = { text[def.name] = it },
                        placeholder = def.default?.let { "默认：${it.content}" } ?: "必填",
                        keyboardType = when (def.type) {
                            ParamType.NUMBER -> KeyboardType.Number
                            ParamType.PATH -> KeyboardType.Uri
                            else -> KeyboardType.Text
                        },
                        textStyle = if (def.type == ParamType.PATH) monoStyle else null,
                    )
                }
            }
        }
        if (lp.trust == PluginTrust.RunsCommands) {
            Text("改动命令中用到的参数后，需要重新确认命令。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 14.dp))
        }
        PrimaryButton(
            "保存参数", icon = "check", enabled = !saving, modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            onClick = {
                val out = LinkedHashMap<String, JsonPrimitive?>()
                for ((k, def) in m.params) {
                    val raw = text[k]?.trim().orEmpty()
                    if (raw.isEmpty()) { out[k] = null; continue }
                    val v = ParamValues.coerce(JsonPrimitive(raw), def.type)
                    if (v == null) { toast.show("「${def.label}」需要是${typeName(def.type)}", icon = "info"); return@PrimaryButton }
                    out[k] = v
                }
                saving = true
                scope.launch {
                    val r = host.saveParams(lp.id, out)
                    saving = false
                    r.fold({ toast.show("参数已保存") }, { toast.show("保存失败：${it.message ?: "未知原因"}", icon = "info") })
                }
            },
        )
    }
}

private fun typeName(t: ParamType) = when (t) {
    ParamType.NUMBER -> "数字"
    ParamType.BOOLEAN -> "开或关"
    ParamType.PATH -> "路径"
    ParamType.STRING -> "文本"
}

@Composable
private fun ManageCard(lp: LoadedPlugin, onExport: () -> Unit, onTerminal: () -> Unit, onRemove: () -> Unit) {
    val c = ReliefTheme.colors
    GlassCard {
        CardHeader("管理", icon = "sliders", caption = "想修改卡片，可以直接告诉 Agent：「修改首页卡片 ${lp.id}」")
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("导出", onClick = onExport, modifier = Modifier.weight(1f), icon = "upload", size = ButtonSize.Small, enabled = lp.manifest != null)
            GlassButton("在终端打开", onClick = onTerminal, modifier = Modifier.weight(1f), icon = "terminal", size = ButtonSize.Small)
        }
        Text(
            "导出的 .hxcard 只包含 plugin.json 等卡片文件，本机参数与确认记录不会带走。",
            style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 12.dp),
        )
        Divider(Modifier.padding(top = 14.dp))
        GlassButton(
            "移除卡片", onClick = onRemove, icon = "trash", contentColor = c.danger,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp), size = ButtonSize.Small,
        )
    }
}
