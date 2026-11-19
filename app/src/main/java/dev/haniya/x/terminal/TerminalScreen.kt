package dev.haniya.x.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassLevel
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.glass
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.component.pressable
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.SessionEvent
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskRecord
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.core.Paths
import dev.haniya.x.ui.Nav
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The terminal workspace: tabs for every open task, the themed Termux view, extra keys, compose bar,
 * scrollback search and paste preview. Leaving this screen never stops processes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(taskId: String?) {
    val context = LocalContext.current
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val settings by App.settings.settings.collectAsState()
    val prefs by TerminalPrefs.prefs.collectAsState()
    val tasks by Runtime.tasks.tasks.collectAsState()
    val focused by Runtime.tasks.focused.collectAsState()
    val controller = remember { TerminalController(context) }

    LaunchedEffect(taskId) { if (taskId != null) Runtime.tasks.focus(taskId) }
    // Entering from home with nothing open: start a shell right away (the home tile says "新建会话").
    // Closing the last tab later keeps the empty state instead of re-spawning.
    LaunchedEffect(Unit) {
        if (taskId == null && TabLabels.openTabs(Runtime.tasks.tasks.value).isEmpty()) {
            runCatching { Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = "Shell", cwd = Paths.GUEST_HOME)) }
                .onFailure { toast.show("无法启动终端：${it.message ?: "未知错误"}", icon = "info") }
        }
    }

    val openTabs = TabLabels.openTabs(tasks)
    val currentId = TabLabels.pick(openTabs, focused)
    val current = openTabs.firstOrNull { it.id == currentId }
    // Re-read the session whenever the task list changes (restart / resume create new sessions).
    val session = remember(currentId, tasks) { currentId?.let { Runtime.tasks.session(it) } }

    var composeOpen by remember { mutableStateOf(settings.composeBarDefault) }
    var searchOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var pastePending by remember { mutableStateOf<String?>(null) }
    var tabMenuFor by remember { mutableStateOf<TaskRecord?>(null) }
    var confirmEnd by remember { mutableStateOf<TaskRecord?>(null) }
    var newTabOpen by remember { mutableStateOf(false) }
    var keysEditorOpen by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    // Screen refresh for the visible task, plus clipboard requests from programs (OSC 52 / paste key).
    LaunchedEffect(currentId) {
        Runtime.tasks.sessionEvents.collect { ev ->
            when (ev) {
                is SessionEvent.TextChanged -> if (ev.taskId == currentId) controller.screenUpdated()
                is SessionEvent.TitleChanged -> Unit
                is SessionEvent.Finished -> if (ev.taskId == currentId) controller.screenUpdated()
                is SessionEvent.Bell -> if (ev.taskId == currentId) controller.haptic()
                is SessionEvent.CopyRequest -> {
                    copyToClipboard(context, ev.text)
                    toast.show("已复制到剪贴板")
                }
                is SessionEvent.PasteRequest -> if (ev.taskId == currentId) {
                    controller.clipboardText()?.let { text ->
                        if (PasteText.needsPreview(text, settings.pastePreviewChars, settings.pastePreviewLines)) pastePending = text
                        else controller.paste(text)
                    }
                }
            }
        }
    }

    // Fullscreen hides the system bars; restore them when leaving.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(fullscreen, activity) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (window != null) WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler {
        when {
            searchOpen -> { searchOpen = false; controller.endSearch() }
            fullscreen -> fullscreen = false
            else -> Nav.back()
        }
    }

    fun doPaste() {
        val text = controller.clipboardText()
        if (text == null) { toast.show("剪贴板里没有文字", icon = "info"); return }
        if (PasteText.needsPreview(text, settings.pastePreviewChars, settings.pastePreviewLines)) pastePending = text
        else if (!controller.paste(text)) toast.show("任务未在运行，无法粘贴", icon = "info")
    }

    // Keys (hardware keyboard, IME, Enter) must reach the terminal, not Compose focus navigation:
    // give the TerminalView focus whenever the visible task changes.
    LaunchedEffect(currentId) {
        if (searchOpen) { searchOpen = false; controller.endSearch() }
    }
    LaunchedEffect(currentId, session) {
        if (currentId != null) {
            delay(50)
            controller.focusTerminal(showKeyboard = false)
        }
    }

    val layout = remember(settings.extraKeys) { ExtraKeys.parse(settings.extraKeys).layout }
    val tp = TerminalTheme.current

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                .imePadding()
                .then(if (imeVisible || fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
        ) {
            if (!fullscreen) {
                TabsBar(
                    tabs = openTabs,
                    currentId = currentId,
                    compact = imeVisible,
                    onSelect = { Runtime.tasks.focus(it) },
                    onMenu = { tabMenuFor = it },
                    onNew = { newTabOpen = true },
                    onBack = { Nav.back() },
                )
            }
            if (searchOpen) {
                SearchBar(controller, onClose = { searchOpen = false; controller.endSearch() })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (fullscreen) 0.dp else 6.dp)) {
                if (current == null) {
                    EmptyTerminal(onShell = { newTabOpen = true })
                } else {
                    TerminalHost(
                        controller = controller,
                        session = session,
                        fontSp = controller.fontSp,
                        inputMode = prefs.inputMode,
                        background = tp.background,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (current.status != TaskStatus.Running) {
                        EndedOverlay(
                            rec = current,
                            onResume = { Runtime.tasks.resume(current.id) },
                            onRestart = { Runtime.tasks.restart(current.id) },
                            onClose = { Runtime.tasks.closeTab(current.id) },
                        )
                    }
                }
            }
            if (composeOpen && current?.status == TaskStatus.Running) {
                ComposeBar(
                    enterAfter = prefs.enterAfterSend,
                    onEnterAfterChange = { v -> TerminalPrefs.update { it.copy(enterAfterSend = v) } },
                    onSend = { text ->
                        val payload = PasteText.composeSend(text, controller.bracketedPaste, prefs.enterAfterSend)
                        controller.write(payload)
                    },
                    onClose = { composeOpen = false },
                )
            }
            if (current?.status == TaskStatus.Running) {
                ExtraKeysBar(
                    layout = layout,
                    visualOf = { k ->
                        when (val a = k.action) {
                            is KeyAction.Sticky -> when (controller.state(a.mod)) {
                                StickyState.Off -> KeyVisual.Idle
                                StickyState.Once -> KeyVisual.Active
                                StickyState.Locked -> KeyVisual.Locked
                            }
                            is KeyAction.Command -> when (a.command) {
                                TerminalCommand.COMPOSE -> if (composeOpen) KeyVisual.Active else KeyVisual.Idle
                                TerminalCommand.SEARCH -> if (searchOpen) KeyVisual.Active else KeyVisual.Idle
                                TerminalCommand.FULLSCREEN -> if (fullscreen) KeyVisual.Active else KeyVisual.Idle
                                else -> KeyVisual.Idle
                            }
                            else -> KeyVisual.Idle
                        }
                    },
                    onKey = { k ->
                        controller.haptic()
                        when (val a = k.action) {
                            is KeyAction.Command -> when (a.command) {
                                TerminalCommand.PASTE -> doPaste()
                                TerminalCommand.COMPOSE -> composeOpen = !composeOpen
                                TerminalCommand.SEARCH -> {
                                    searchOpen = !searchOpen
                                    if (searchOpen) controller.beginSearch() else controller.endSearch()
                                }
                                TerminalCommand.FULLSCREEN -> fullscreen = !fullscreen
                                TerminalCommand.KEYBOARD -> if (imeVisible) controller.hideIme() else controller.focusTerminal(true)
                            }
                            else -> controller.press(k)
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { keysEditorOpen = true },
                    ),
                )
            }
        }

        // ---- sheets ----
        PastePreviewSheet(
            text = pastePending,
            onPaste = { text -> controller.paste(text); pastePending = null },
            onDismiss = { pastePending = null },
        )
        TabActionsSheet(
            rec = tabMenuFor,
            onDismiss = { tabMenuFor = null },
            onLeave = { tabMenuFor = null; Nav.back() },
            onCloseTab = { r -> tabMenuFor = null; Runtime.tasks.closeTab(r.id); toast.show("标签已关闭，任务仍在后台运行") },
            onTerminate = { r -> tabMenuFor = null; confirmEnd = r },
            onEditKeys = { tabMenuFor = null; keysEditorOpen = true },
        )
        ConfirmEndSheet(
            rec = confirmEnd,
            onDismiss = { confirmEnd = null },
            onConfirm = { r ->
                confirmEnd = null
                Runtime.tasks.terminate(r.id)
                scope.launch { delay(300); toast.show("已发送结束信号") }
            },
        )
        NewTabSheet(visible = newTabOpen, onDismiss = { newTabOpen = false })
        ExtraKeysEditorSheet(visible = keysEditorOpen, onDismiss = { keysEditorOpen = false })
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
}

// ---------------------------------------------------------------- tabs

@Composable
private fun TabsBar(
    tabs: List<TaskRecord>,
    currentId: String?,
    compact: Boolean,
    onSelect: (String) -> Unit,
    onMenu: (TaskRecord) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    val c = ReliefTheme.colors
    val agentNames = remember { App.agents.agents.associate { it.id to it.name } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (compact) 4.dp else 8.dp, bottom = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!compact) IconCircleButton("chevL", contentDescription = "返回首页（任务继续运行）", onClick = onBack, size = 40.dp, iconSize = 18.dp)
        // Keep the focused tab visible (new tabs are appended at the end, often off-screen).
        val tabScroll = rememberScrollState()
        val tabX = remember { mutableMapOf<String, Int>() }
        LaunchedEffect(currentId, tabs.size) {
            val id = currentId ?: return@LaunchedEffect
            // A new tab is measured a frame or two after it is focused: wait for its position.
            repeat(30) {
                val x = tabX[id]
                if (x != null) { tabScroll.animateScrollTo((x - 24).coerceAtLeast(0)); return@LaunchedEffect }
                delay(16)
            }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(tabScroll).padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in tabs) {
                val selected = t.id == currentId
                val shape = RoundedCornerShape(Radii.pill)
                Row(
                    Modifier
                        .onGloballyPositioned { tabX[t.id] = it.positionInParent().x.toInt() }
                        .heightIn(min = if (compact) 32.dp else 38.dp)
                        .widthIn(max = 220.dp)
                        .then(if (selected) Modifier.insetSurface(shape, depth = 3.dp) else Modifier.glass(shape, GlassLevel.Thin))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (selected) onMenu(t) else onSelect(t.id) },
                            onLongClick = { onMenu(t) },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = TabLabels.title(t, agentNames[t.agentId]) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(t.status)
                    HIcon(if (t.kind == TaskKind.Agent) "bot" else "terminal", size = 16.dp, tint = if (selected) c.mid else c.ink2)
                    Text(
                        TabLabels.title(t, agentNames[t.agentId]),
                        style = ReliefTheme.type.label,
                        color = if (selected) c.mid else c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        translate = false,
                    )
                }
            }
        }
        IconCircleButton("plus", contentDescription = "新建标签", onClick = onNew, size = if (compact) 34.dp else 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun StatusDot(status: TaskStatus) {
    val c = ReliefTheme.colors
    val color = when (status) {
        TaskStatus.Running -> c.accent
        TaskStatus.Exited -> c.ink2
        TaskStatus.KilledBySystem -> c.amber
    }
    Box(Modifier.size(7.dp).background(color, CircleShape))
}

// ---------------------------------------------------------------- empty / ended

@Composable
private fun EmptyTerminal(onShell: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                dev.haniya.relief.component.IconTile("terminal", size = 56.dp, filled = false)
                Text("还没有打开的标签", style = ReliefTheme.type.h3)
                Text("新建一个 Shell，或在项目里启动智能体。正在运行的任务可以从首页「进行中的任务」找回。", style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2)
                PrimaryButton("新建标签", onClick = onShell, icon = "plus")
            }
        }
    }
}

@Composable
private fun EndedOverlay(rec: TaskRecord, onResume: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    val c = ReliefTheme.colors
    val ended = TabLabels.ended(rec) ?: return
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = if (rec.status == TaskStatus.KilledBySystem) 1f else 0.72f)), contentAlignment = Alignment.BottomCenter) {
        GlassCard(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HIcon(if (rec.status == TaskStatus.KilledBySystem) "warn" else "info", size = 20.dp, tint = if (rec.status == TaskStatus.KilledBySystem) c.amberInk else c.ink2)
                Text(ended.title, style = ReliefTheme.type.h3)
            }
            Spacer(Modifier.heightIn(min = 6.dp))
            Text(ended.detail, style = ReliefTheme.type.caption, color = c.ink2)
            if (rec.resumeCommand != null) {
                Text("「恢复会话」会在同一目录用 ${rec.resumeCommand} 继续上次的对话。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rec.resumeCommand != null) PrimaryButton("恢复会话", onClick = onResume, size = ButtonSize.Small, icon = "history")
                GlassButton("重新启动", onClick = onRestart, size = ButtonSize.Small, icon = "refresh")
                GlassButton("关闭标签", onClick = onClose, size = ButtonSize.Small)
            }
        }
    }
}

// ---------------------------------------------------------------- compose bar

@Composable
private fun ComposeBar(enterAfter: Boolean, onEnterAfterChange: (Boolean) -> Unit, onSend: (String) -> Unit, onClose: () -> Unit) {
    val c = ReliefTheme.colors
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassField(
            value = text,
            onValueChange = { text = it },
            placeholder = "在这里输入（支持中文输入法与粘贴），写完点发送",
            singleLine = false,
            minHeight = 48.dp,
            modifier = Modifier.heightIn(max = 160.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallRoundButton("close", contentDescription = "收起撰写栏", onClick = onClose)
            dev.haniya.relief.component.HChip("发送后回车", selected = enterAfter, onClick = { onEnterAfterChange(!enterAfter) })
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                "发送", size = ButtonSize.Small, icon = "send", enabled = text.isNotEmpty(),
                onClick = { onSend(text); text = "" },
            )
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchBar(controller: TerminalController, onClose: () -> Unit) {
    val c = ReliefTheme.colors
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var mark by remember { mutableStateOf(0L) }
    var index by remember { mutableIntStateOf(-1) }

    fun search() {
        if (query.isEmpty()) { matches = emptyList(); index = -1; controller.clearHighlight(); return }
        mark = controller.scrollMark()
        matches = ScrollbackMatcher.find(controller.snapshotRows(), query)
        index = matches.lastIndex
        if (index >= 0 && !controller.showMatch(matches[index], mark, query)) search()
        else if (index < 0) controller.clearHighlight()
    }
    fun step(delta: Int) {
        if (matches.isEmpty()) return
        index = (index + delta).mod(matches.size)
        if (!controller.showMatch(matches[index], mark, query)) search()
    }
    LaunchedEffect(query) { delay(180); search() }
    // The terminal reflows when the keyboard opens/closes or the window resizes: row numbers captured by the last
    // search no longer point at the match. Re-locate the current match on the new layout.
    LaunchedEffect(Unit) {
        var rows = controller.emulator?.mRows ?: 0
        var cols = controller.emulator?.mColumns ?: 0
        while (true) {
            delay(300)
            val e = controller.emulator ?: continue
            if (e.mRows != rows || e.mColumns != cols) {
                rows = e.mRows; cols = e.mColumns
                if (query.isNotEmpty()) {
                    val keep = index
                    mark = controller.scrollMark()
                    matches = ScrollbackMatcher.find(controller.snapshotRows(), query)
                    index = if (matches.isEmpty()) -1 else keep.coerceIn(0, matches.lastIndex)
                    if (index >= 0) controller.showMatch(matches[index], mark, query) else controller.clearHighlight()
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassField(
            value = query, onValueChange = { query = it }, placeholder = "搜索回滚内容",
            modifier = Modifier.weight(1f),
            trailing = {
                Text(
                    if (query.isEmpty()) "" else if (matches.isEmpty()) "无结果" else "${index + 1}/${matches.size}",
                    style = ReliefTheme.type.caption, color = c.ink2,
                )
            },
        )
        SmallRoundButton("chevU", contentDescription = "上一个", onClick = { step(-1) })
        SmallRoundButton("chevD", contentDescription = "下一个", onClick = { step(1) })
        SmallRoundButton("close", contentDescription = "关闭搜索", onClick = onClose)
    }
}

// ---------------------------------------------------------------- sheets

@Composable
private fun PastePreviewSheet(text: String?, onPaste: (String) -> Unit, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    dev.haniya.relief.component.GlassSheet(visible = text != null, onDismiss = onDismiss, title = "粘贴预览") {
        val t = text ?: return@GlassSheet
        val stats = remember(t) { PasteText.stats(t) }
        val preview = remember(t) { PasteText.previewLines(t) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${stats.chars} 个字符 · ${stats.lines} 行。确认内容无误后再发送到终端。", style = ReliefTheme.type.caption, color = c.ink2)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).insetSurface(RoundedCornerShape(Radii.sm))
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                for (line in preview) Text(line, style = ReliefTheme.type.caption.copy(fontFamily = FontFamily.Monospace), translate = false)
                if (stats.lines > preview.size) Text("… 另外 ${stats.lines - preview.size} 行", style = ReliefTheme.type.caption, color = c.ink2)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("粘贴", onClick = { onPaste(t) }, icon = "paste")
                if (stats.lines > 1) GlassButton("作为一行粘贴", onClick = { onPaste(PasteText.joinAsOneLine(t)) })
            }
        }
    }
}

@Composable
private fun TabActionsSheet(
    rec: TaskRecord?,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onCloseTab: (TaskRecord) -> Unit,
    onTerminate: (TaskRecord) -> Unit,
    onEditKeys: () -> Unit,
) {
    val c = ReliefTheme.colors
    dev.haniya.relief.component.GlassSheet(visible = rec != null, onDismiss = onDismiss, title = "标签操作") {
        val r = rec ?: return@GlassSheet
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(r.lastTitle?.takeIf { it.isNotBlank() } ?: r.title, style = ReliefTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            Text(r.cwd, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
            Spacer(Modifier.heightIn(min = 8.dp))
            dev.haniya.relief.component.ListRow("离开（保持运行）", icon = "home", subtitle = "回到首页，任务继续运行，随时回来", onClick = onLeave)
            dev.haniya.relief.component.Divider()
            dev.haniya.relief.component.ListRow("关闭标签", icon = "close", subtitle = "只收起标签，任务继续在后台运行，可在首页「进行中的任务」找回", onClick = { onCloseTab(r) })
            if (r.status == TaskStatus.Running) {
                dev.haniya.relief.component.Divider()
                dev.haniya.relief.component.ListRow("结束任务", icon = "power", titleColor = c.danger, subtitle = "终止进程，未保存的工作可能丢失", onClick = { onTerminate(r) })
            }
            dev.haniya.relief.component.Divider()
            dev.haniya.relief.component.ListRow("自定义扩展键", icon = "keyboard", subtitle = "也可以长按扩展键栏打开", onClick = onEditKeys)
        }
    }
}

@Composable
private fun ConfirmEndSheet(rec: TaskRecord?, onDismiss: () -> Unit, onConfirm: (TaskRecord) -> Unit) {
    val c = ReliefTheme.colors
    dev.haniya.relief.component.GlassSheet(visible = rec != null, onDismiss = onDismiss, title = "结束任务") {
        val r = rec ?: return@GlassSheet
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("结束「${r.title}」？", style = ReliefTheme.type.title)
            Text(
                "会先请求程序正常退出，几秒后仍未退出则强制终止。正在进行的操作和未保存的内容可能丢失。" +
                    if (r.resumeCommand != null) "之后可以用「恢复会话」继续这次对话。" else "",
                style = ReliefTheme.type.body, color = c.ink2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("结束任务", onClick = { onConfirm(r) }, color = c.danger)
                GlassButton("取消", onClick = onDismiss)
            }
        }
    }
}
