package dev.haniya.x.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.HChip
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.agents.AgentLaunchSheet
import dev.haniya.x.agents.LaunchRequest
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec

/** New tab: a shell in the home dir or a project, or an agent (delegated to the agents launch sheet). */
@Composable
internal fun NewTabSheet(visible: Boolean, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val projects by App.projects.projects.collectAsState()
    var agentRequest by remember { mutableStateOf<LaunchRequest?>(null) }

    fun shell(path: String, title: String) {
        runCatching {
            Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = title, cwd = path))
            if (path != Paths.GUEST_HOME) App.projects.touch(path)
        }.onFailure { toast.show("无法启动终端：${it.message ?: "未知错误"}", icon = "info") }
        onDismiss()
    }

    GlassSheet(visible = visible, onDismiss = onDismiss, title = "新建标签") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("新建标签", style = ReliefTheme.type.title)
            Text("同一个项目可以并行开多个标签。", style = ReliefTheme.type.caption, color = c.ink2)
            ListRow("启动智能体", icon = "bot", subtitle = "Claude Code · Codex · OpenCode", onClick = {
                onDismiss()
                agentRequest = LaunchRequest()
            })
            Divider()
            ListRow("Shell · 主目录", icon = "terminal", subtitle = Paths.GUEST_HOME, onClick = { shell(Paths.GUEST_HOME, "Shell") })
            for (p in projects.take(8)) {
                Divider()
                ListRow("Shell · ${p.name}", icon = "folder", subtitle = p.path, onClick = { shell(p.path, p.name) })
            }
        }
    }
    AgentLaunchSheet(request = agentRequest, onDismiss = { agentRequest = null })
}

/** Reorder / remove / add extra keys; persisted to settings.extraKeys. */
@Composable
internal fun ExtraKeysEditorSheet(visible: Boolean, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val settings by App.settings.settings.collectAsState()
    var layout by remember(visible) { mutableStateOf(ExtraKeys.parse(settings.extraKeys).layout) }
    var row by remember(visible) { mutableIntStateOf(0) }
    var selected by remember(visible) { mutableIntStateOf(-1) }
    var macroLabel by remember { mutableStateOf("") }
    var macroText by remember { mutableStateOf("") }

    fun save(l: ExtraKeysLayout) {
        layout = ExtraKeysEdit.normalized(l)
        App.settings.update { it.copy(extraKeys = ExtraKeys.encode(layout)) }
    }

    GlassSheet(visible = visible, onDismiss = onDismiss, title = "自定义扩展键") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自定义扩展键", style = ReliefTheme.type.title)
            Text("点选一个键后可移动或删除；从下方添加新键。", style = ReliefTheme.type.caption, color = c.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layout.rows.forEachIndexed { i, _ -> HChip("第 ${i + 1} 行", selected = row == i, onClick = { row = i; selected = -1 }) }
                if (layout.rows.size < 4) HChip("+ 新行", selected = false, onClick = { save(ExtraKeysEdit.addRow(layout)); row = layout.rows.lastIndex })
            }
            val keys = layout.rows.getOrNull(row).orEmpty()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEachIndexed { i, k -> HChip(k.label, selected = selected == i, onClick = { selected = if (selected == i) -1 else i }) }
            }
            if (selected in keys.indices) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallRoundButton("chevL", contentDescription = "左移", onClick = { save(ExtraKeysEdit.move(layout, row, selected, -1)); selected = (selected - 1).coerceAtLeast(0) })
                    SmallRoundButton("chevR", contentDescription = "右移", onClick = { save(ExtraKeysEdit.move(layout, row, selected, 1)); selected = (selected + 1).coerceAtMost(keys.lastIndex) })
                    if (layout.rows.size > 1) GlassButton("移到其他行", size = ButtonSize.Small, onClick = {
                        save(ExtraKeysEdit.moveToRow(layout, row, selected, (row + 1) % layout.rows.size)); selected = -1
                    })
                    GlassButton("删除", size = ButtonSize.Small, contentColor = c.ink, onClick = { save(ExtraKeysEdit.remove(layout, row, selected)); selected = -1 })
                }
            }
            Divider()
            for (group in ExtraKeys.catalogue) {
                Text(group.title, style = ReliefTheme.type.label, color = c.ink2)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in group.keys) HChip(k.label, selected = false, onClick = { ExtraKeysEdit.add(layout, row, k)?.let(::save) })
                }
            }
            Text("文字宏（例如 git status⏎，用 \\n 表示回车）", style = ReliefTheme.type.label, color = c.ink2)
            GlassField(macroLabel, { macroLabel = it.take(8) }, placeholder = "按键名称")
            GlassField(macroText, { macroText = it }, placeholder = "发送的内容")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("添加宏", size = ButtonSize.Small, enabled = macroLabel.isNotBlank() && macroText.isNotEmpty(), onClick = {
                    ExtraKeysEdit.add(layout, row, ExtraKeys.macro(macroLabel, ExtraKeys.unescapeMacro(macroText)))?.let(::save)
                    macroLabel = ""; macroText = ""
                })
                GlassButton("恢复默认", size = ButtonSize.Small, onClick = {
                    layout = ExtraKeys.DEFAULT
                    App.settings.update { it.copy(extraKeys = null) }
                })
            }
            val mode by TerminalPrefs.prefs.collectAsState()
            Divider()
            Text("输入方式", style = ReliefTheme.type.label, color = c.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HChip("文字（支持中文输入法）", selected = mode.inputMode == TerminalInputMode.Text, onClick = { TerminalPrefs.update { it.copy(inputMode = TerminalInputMode.Text) } })
                HChip("逐字符", selected = mode.inputMode == TerminalInputMode.CharByChar, onClick = { TerminalPrefs.update { it.copy(inputMode = TerminalInputMode.CharByChar) } })
            }
        }
    }
}
