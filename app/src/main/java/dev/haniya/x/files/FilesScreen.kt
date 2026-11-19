package dev.haniya.x.files

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.HChip
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.component.pressable
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskKind
import dev.haniya.x.runtime.TaskSpec
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.PageHeader
import dev.haniya.x.ui.Route
import dev.haniya.x.ui.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Files of the workspace (and, with permission, phone storage). [pickProject]: choose a folder to add as a project.
 */
@Composable
fun FilesScreen(start: String?, pickProject: Boolean) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val phoneGranted by PhoneStorage.granted.collectAsState()
    var path by remember { mutableStateOf(GuestPaths.normalize(start ?: Paths.GUEST_HOME)) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }

    // Re-check the storage permission whenever the screen resumes (the user may come back from system settings).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { if (PhoneStorage.refreshAsync(context)) reload++ } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val onPhone = GuestPaths.isPhoneStorage(path)
    LaunchedEffect(path, showHidden, reload, phoneGranted) {
        entries = null; error = null
        if (onPhone && !phoneGranted) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { FileStore.list(path, showHidden) } }
            .onSuccess { entries = it }.onFailure { error = it.message ?: "无法读取" }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileStore.import(context, uris, path) } }
                .onSuccess { toast.show("已导入 ${it.size} 个文件"); reload++ }
                .onFailure { toast.show(it.message ?: "导入失败", icon = "info") }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = exportPath ?: return@rememberLauncherForActivityResult
        exportPath = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileStore.export(context, p, uri) } }
                .onSuccess { toast.show("已保存到手机") }.onFailure { toast.show(it.message ?: "保存失败", icon = "info") }
        }
    }

    val root = when {
        onPhone -> GuestPaths.PHONE
        else -> Paths.GUEST_HOME
    }
    val parent = GuestPaths.parent(path)?.takeIf { path != root && (it.startsWith(root) || it == root) }
    BackHandler { if (parent != null) path = parent else Nav.back() }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(
            if (pickProject) "选择项目文件夹" else "文件",
            caption = GuestPaths.display(path),
            actions = {
                IconCircleButton(if (showHidden) "eye" else "eyeOff", contentDescription = if (showHidden) "隐藏以 . 开头的文件" else "显示隐藏文件", onClick = { showHidden = !showHidden }, size = 40.dp, iconSize = 18.dp)
                if (!pickProject) IconCircleButton("download", contentDescription = "从手机导入文件", onClick = { importer.launch(arrayOf("*/*")) }, size = 40.dp, iconSize = 18.dp)
                IconCircleButton("plus", contentDescription = "新建文件夹", onClick = { newFolder = true }, size = 40.dp, iconSize = 18.dp)
            },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HChip("主目录", selected = !onPhone && !path.startsWith(Paths.GUEST_PROJECTS), onClick = { path = Paths.GUEST_HOME })
            HChip("项目", selected = path.startsWith(Paths.GUEST_PROJECTS), onClick = { path = Paths.GUEST_PROJECTS.also { runCatching { FileStore.host(it).mkdirs() } } })
            HChip("手机存储", selected = onPhone, onClick = { path = GuestPaths.PHONE })
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (onPhone && !phoneGranted) {
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile("folder", size = 40.dp)
                            Text("访问手机存储", style = ReliefTheme.type.h3, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "授权后可以在这里浏览手机里的文件夹，把其中的文件夹作为项目；终端和智能体也能在 /sdcard 下读写这些文件。",
                            style = ReliefTheme.type.body, color = c.ink2,
                        )
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton("前往授权", icon = "key", size = ButtonSize.Small, onClick = {
                            try { context.startActivity(PhoneStorage.settingsIntent(context)) } catch (_: ActivityNotFoundException) { toast.show("请在系统设置中为 HaniyaX 开启「所有文件访问权限」", icon = "info") }
                        })
                    }
                }
                return@LazyColumn
            }
            if (onPhone && pickProject) {
                item { PhoneStorageNote() }
            }
            if (parent != null) {
                item {
                    EntryRow(icon = "chevU", title = "上一级", subtitle = GuestPaths.display(parent), onClick = { path = parent })
                    Divider()
                }
            }
            val list = entries
            when {
                error != null -> item { Text(error!!, style = ReliefTheme.type.body, color = c.ink2, modifier = Modifier.padding(vertical = 18.dp)) }
                list == null -> item { Text("读取中…", style = ReliefTheme.type.body, color = c.ink2, modifier = Modifier.padding(vertical = 18.dp)) }
                list.isEmpty() -> item { Text("这个文件夹是空的", style = ReliefTheme.type.body, color = c.ink2, modifier = Modifier.padding(vertical = 18.dp)) }
                else -> itemsIndexed(list, key = { _, e -> e.path }) { i, e ->
                    if (i > 0) Divider()
                    EntryRow(
                        icon = if (e.isDir) "folder" else if (GuestPaths.isTextName(e.name)) "note" else "copy",
                        title = e.name + if (e.isLink) " ↗" else "",
                        subtitle = if (e.isDir) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.modified))
                        else "${formatSize(e.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.modified))}",
                        onClick = { if (e.isDir) path = e.path else selected = e },
                        onMore = { selected = e },
                    )
                }
            }
        }
        if (pickProject && !(onPhone && !phoneGranted)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
                PrimaryButton("使用「${path.substringAfterLast('/').ifEmpty { "/" }}」作为项目", icon = "check", modifier = Modifier.fillMaxWidth(), onClick = {
                    App.projects.add(path)
                    toast.show("已添加到最近项目")
                    Nav.back()
                })
            }
        }
    }

    EntrySheet(
        entry = selected,
        onDismiss = { selected = null },
        onChanged = { reload++ },
        onExport = { p -> exportPath = p; exporter.launch(p.substringAfterLast('/')) },
        onOpenFolder = { p -> selected = null; path = p },
    )
    NewFolderSheet(visible = newFolder, dir = path, onDismiss = { newFolder = false }, onCreated = { newFolder = false; reload++ })
}

@Composable
private fun PhoneStorageNote() {
    val c = ReliefTheme.colors
    Column(Modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(14.dp)) {
        Text("放在手机存储里的项目", style = ReliefTheme.type.label)
        Spacer(Modifier.height(4.dp))
        Text(
            "手机存储不支持符号链接和可执行权限，读写也慢一些：npm install、部分构建工具和脚本可能无法正常工作。需要安装依赖的项目建议放在工作区的「项目」里，并通过系统文件管理中的 HaniyaX 访问。",
            style = ReliefTheme.type.caption, color = c.ink2,
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EntryRow(icon: String, title: String, subtitle: String?, onClick: () -> Unit, onMore: (() -> Unit)? = null) {
    val c = ReliefTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(pressedScale = 1f, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, size = 36.dp, filled = false)
        Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            if (subtitle != null) Text(subtitle, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, translate = false)
        }
        if (onMore != null) Box(Modifier.pressable(onClick = onMore).padding(10.dp)) { HIcon("list", size = 18.dp, tint = c.ink2) }
    }
}

@Composable
private fun EntrySheet(
    entry: FileEntry?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onExport: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<FileEntry?>(null) }
    if (entry != null) shown = entry
    GlassSheet(visible = entry != null, onDismiss = onDismiss, title = shown?.name) {
        val e = shown ?: return@GlassSheet
        var text by remember(e.path) { mutableStateOf<String?>(null) }
        var truncated by remember(e.path) { mutableStateOf(false) }
        var binary by remember(e.path) { mutableStateOf(false) }
        var editing by remember(e.path) { mutableStateOf(false) }
        var draft by remember(e.path) { mutableStateOf("") }
        var renaming by remember(e.path) { mutableStateOf(false) }
        var newName by remember(e.path) { mutableStateOf(e.name) }
        var confirmDelete by remember(e.path) { mutableStateOf(false) }
        LaunchedEffect(e.path) {
            if (!e.isDir && e.size <= 4L * 1024 * 1024) {
                val r = runCatching { withContext(Dispatchers.IO) { FileStore.readText(e.path) } }.getOrNull()
                if (r == null) binary = true else { text = r.first; truncated = r.second; draft = r.first }
            } else if (!e.isDir) binary = true
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(e.name, style = ReliefTheme.type.title, maxLines = 2, translate = false)
            Text(GuestPaths.display(e.path), style = ReliefTheme.type.caption, color = c.ink2, translate = false)
            Spacer(Modifier.height(12.dp))
            if (!e.isDir) {
                val t = text
                when {
                    editing -> {
                        GlassField(draft, { draft = it }, singleLine = false, minHeight = 160.dp,
                            textStyle = ReliefTheme.type.caption.copy(fontFamily = FontFamily.Monospace))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("保存", size = ButtonSize.Small, icon = "check", onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { FileStore.writeText(e.path, draft) } }
                                        .onSuccess { text = draft; editing = false; toast.show("已保存"); onChanged() }
                                        .onFailure { toast.show(it.message ?: "保存失败", icon = "info") }
                                }
                            })
                            GlassButton("取消", size = ButtonSize.Small, onClick = { draft = text ?: ""; editing = false })
                        }
                    }
                    t != null -> {
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text(t.ifEmpty { dev.haniya.relief.i18n.I18n.tr("（空文件）") }, style = ReliefTheme.type.caption.copy(fontFamily = FontFamily.Monospace), translate = false)
                        }
                        if (truncated) Text("文件较大，只显示了开头部分。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                    }
                    binary -> Text("这个文件无法在这里预览，可以用其他应用打开或保存到手机。", style = ReliefTheme.type.body, color = c.ink2)
                    else -> Text("读取中…", style = ReliefTheme.type.body, color = c.ink2)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (e.isDir) {
                ListRow("打开文件夹", icon = "folder", onClick = { onOpenFolder(e.path) })
                Divider()
                ListRow("在此打开终端", icon = "terminal", onClick = {
                    runCatching { Runtime.tasks.start(TaskSpec(kind = TaskKind.Shell, title = e.name, cwd = e.path)) }
                        .onSuccess { onDismiss(); Nav.go(Route.Terminal(it)) }
                        .onFailure { toast.show(it.message ?: "无法启动终端", icon = "info") }
                })
                Divider()
                ListRow("添加到最近项目", icon = "pin", onClick = { App.projects.add(e.path); toast.show("已添加到最近项目"); onDismiss() })
            } else {
                if (text != null && !truncated && !editing) {
                    ListRow("编辑", icon = "edit", onClick = { editing = true })
                    Divider()
                }
                ListRow("用其他应用打开", icon = "arrow", onClick = { share(context, e, Intent.ACTION_VIEW)?.let { toast.show(it, icon = "info") } })
                Divider()
                ListRow("分享", icon = "send", onClick = { share(context, e, Intent.ACTION_SEND)?.let { toast.show(it, icon = "info") } })
                Divider()
                ListRow("保存到手机", icon = "upload", onClick = { onExport(e.path) })
            }
            Divider()
            if (!renaming) ListRow("重命名", icon = "edit", onClick = { renaming = true })
            else Column(Modifier.padding(vertical = 8.dp)) {
                GlassField(newName, { newName = it }, placeholder = "新名称")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("确定", size = ButtonSize.Small, onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { FileStore.rename(e.path, newName.trim()) } }
                                .onSuccess { onDismiss(); onChanged() }
                                .onFailure { toast.show(it.message ?: "重命名失败", icon = "info") }
                        }
                    })
                    GlassButton("取消", size = ButtonSize.Small, onClick = { renaming = false; newName = e.name })
                }
            }
            Divider()
            if (!confirmDelete) ListRow("删除", icon = "trash", titleColor = c.danger, onClick = { confirmDelete = true })
            else Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (e.isDir) "删除这个文件夹及其中所有内容？无法恢复。" else "删除这个文件？无法恢复。", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f))
                GlassButton("删除", size = ButtonSize.Small, contentColor = c.danger, onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { FileStore.delete(e.path) } }
                            .onSuccess { toast.show("已删除"); onDismiss(); onChanged() }
                            .onFailure { toast.show(it.message ?: "删除失败", icon = "info") }
                    }
                })
            }
        }
    }
}

/** Opens or shares a file through a FileProvider content:// URI. Returns an error message or null. */
private fun share(context: android.content.Context, e: FileEntry, action: String): String? {
    val file = FileStore.host(e.path)
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (ex: IllegalArgumentException) {
        return "这个位置的文件无法分享"
    }
    val mime = FileStore.mimeOf(e.name)
    val intent = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (action == Intent.ACTION_SEND) intent.setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else intent.setDataAndType(uri, mime)
    return try {
        context.startActivity(Intent.createChooser(intent, e.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "没有可以打开这个文件的应用"
    }
}

@Composable
private fun NewFolderSheet(visible: Boolean, dir: String, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var name by remember(visible) { mutableStateOf("") }
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "新建文件夹") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("位置：${GuestPaths.display(dir)}", style = ReliefTheme.type.caption, color = ReliefTheme.colors.ink2)
            GlassField(name, { name = it }, placeholder = "文件夹名称")
            GuestPaths.validName(name).takeIf { name.isNotEmpty() }?.let { Text(it, style = ReliefTheme.type.caption, color = ReliefTheme.colors.amberInk) }
            PrimaryButton("创建", enabled = name.isNotBlank() && GuestPaths.validName(name) == null, onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { FileStore.mkdir(dir, name.trim()) } }
                        .onSuccess { onCreated() }.onFailure { toast.show(it.message ?: "创建失败", icon = "info") }
                }
            })
        }
    }
}

internal fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
