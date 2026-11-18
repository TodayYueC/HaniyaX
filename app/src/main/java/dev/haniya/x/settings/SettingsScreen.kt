package dev.haniya.x.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.CheckCircle
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.FieldLabel
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassField
import dev.haniya.relief.component.GlassLevel
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.Stepper
import dev.haniya.relief.component.SwitchRow
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.glass
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.theme.ReliefPalette
import dev.haniya.relief.theme.ReliefPalettes
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.BuildConfig
import dev.haniya.x.core.Paths
import dev.haniya.x.home.Fmt
import dev.haniya.x.home.ItemRow
import dev.haniya.x.home.SectionLabel
import dev.haniya.x.home.copyToClipboard
import dev.haniya.x.home.monoStyle
import dev.haniya.x.home.openUrl
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.Settings as SystemSettings

/** 设置: appearance, terminal, background running, agents, workspace, about. */
@Composable
fun SettingsScreen() {
    val s by App.settings.settings.collectAsState()
    var resetOpen by remember { mutableStateOf(false) }

    Page(
        title = "设置",
        overlay = { ResetSheet(resetOpen, onDismiss = { resetOpen = false }) },
    ) {
        SectionLabel("外观")
        AppearanceCard(s.palette)
        SectionLabel("终端")
        TerminalCard(s)
        SectionLabel("后台运行")
        BackgroundCard(s.wakeLock)
        PhantomCard()
        SectionLabel("智能体")
        AgentsCard(s.agentSdkHints)
        SectionLabel("工作区")
        WorkspaceCard(onReset = { resetOpen = true })
        SectionLabel("关于")
        AboutCard()
    }
}

// ---- 外观 ----------------------------------------------------------------------------------------------------

@Composable
private fun AppearanceCard(current: ReliefPalette?) {
    GlassCard {
        ItemRow(
            title = "跟随系统",
            subtitle = "浅色时用铂雾，深色时用曜石",
            translate = true,
            leading = { IconTile("palette", size = 34.dp, filled = false) },
            trailing = { CheckCircle(current == null, onClick = null, size = 24.dp, label = "跟随系统") },
            onClick = { App.settings.update { it.copy(palette = null) } },
        )
        ReliefPalette.entries.forEach { p ->
            Divider()
            ItemRow(
                title = p.label,
                subtitle = if (p.isDark) "深色" else "浅色",
                translate = true,
                leading = { Swatch(p) },
                trailing = { CheckCircle(current == p, onClick = null, size = 24.dp, label = p.label) },
                onClick = { App.settings.update { it.copy(palette = p) } },
            )
        }
    }
}

@Composable
private fun Swatch(p: ReliefPalette) {
    val (a, b) = ReliefPalettes.swatch(p)
    Box(Modifier.size(34.dp).glass(CircleShape, GlassLevel.Thin), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))))
    }
}

// ---- 终端 ----------------------------------------------------------------------------------------------------

@Composable
private fun TerminalCard(s: AppSettings) {
    val c = ReliefTheme.colors
    GlassCard {
        StepperRow(
            title = "字号", value = "${s.terminalFontSp} sp",
            minusEnabled = s.terminalFontSp > SettingsStore.FONT_MIN, plusEnabled = s.terminalFontSp < SettingsStore.FONT_MAX,
            onMinus = { App.settings.update { it.copy(terminalFontSp = it.terminalFontSp - 1) } },
            onPlus = { App.settings.update { it.copy(terminalFontSp = it.terminalFontSp + 1) } },
        )
        val size = s.terminalFontSp
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)
                .insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                "~/projects $ git status\n中文输入 Aa 0O 1lI {}",
                style = monoStyle.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp),
                color = c.ink, maxLines = 2, overflow = TextOverflow.Clip, translate = false,
            )
        }
        Divider()
        StepperRow(
            title = "粘贴预览", subtitle = "超过这么多字符时先预览", value = "${s.pastePreviewChars} 字",
            minusEnabled = s.pastePreviewChars > SettingsStore.PASTE_CHARS_MIN, plusEnabled = s.pastePreviewChars < SettingsStore.PASTE_CHARS_MAX,
            onMinus = { App.settings.update { it.copy(pastePreviewChars = PasteSteps.chars(it.pastePreviewChars, up = false)) } },
            onPlus = { App.settings.update { it.copy(pastePreviewChars = PasteSteps.chars(it.pastePreviewChars, up = true)) } },
        )
        Divider()
        StepperRow(
            title = "或超过", subtitle = "行数达到时也先预览", value = "${s.pastePreviewLines} 行",
            minusEnabled = s.pastePreviewLines > SettingsStore.PASTE_LINES_MIN, plusEnabled = s.pastePreviewLines < SettingsStore.PASTE_LINES_MAX,
            onMinus = { App.settings.update { it.copy(pastePreviewLines = PasteSteps.lines(it.pastePreviewLines, up = false)) } },
            onPlus = { App.settings.update { it.copy(pastePreviewLines = PasteSteps.lines(it.pastePreviewLines, up = true)) } },
        )
        Divider()
        SwitchRow(
            "默认打开撰写栏", s.composeBarDefault,
            onCheckedChange = { on -> App.settings.update { it.copy(composeBarDefault = on) } },
            subtitle = "键盘弹出时先在输入框里写好再发送，中文输入更顺手",
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    subtitle: String? = null,
) {
    val c = ReliefTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.bodyMedium, color = c.ink)
            if (subtitle != null) Text(subtitle, style = ReliefTheme.type.caption, color = c.ink2)
        }
        Stepper(
            onMinus = onMinus, onPlus = onPlus, modifier = Modifier.width(156.dp),
            minusEnabled = minusEnabled, plusEnabled = plusEnabled, buttonSize = 40.dp,
        ) {
            Text(value, style = ReliefTheme.type.label, color = c.ink, maxLines = 1, translate = false)
        }
    }
}

// ---- 后台运行 --------------------------------------------------------------------------------------------------

/** Increments every time the screen resumes, so system-state reads refresh after returning from Settings. */
@Composable
private fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

private fun ignoringBatteryOptimizations(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

private fun requestIgnoreBattery(context: Context): Boolean {
    val direct = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    return startSafely(context, direct) ||
        startSafely(context, Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
        startSafely(context, Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

@Composable
private fun BackgroundCard(wakeLock: Boolean) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val tick = rememberResumeTick()
    val ignoring = remember(tick) { ignoringBatteryOptimizations(context) }
    GlassCard {
        SwitchRow(
            "保持唤醒", wakeLock,
            onCheckedChange = { on -> App.settings.update { it.copy(wakeLock = on) } },
            subtitle = "有任务运行时阻止 CPU 休眠，耗电会多一些",
            icon = "power",
        )
        Divider()
        ListRow(
            "忽略电池优化",
            icon = "zap",
            subtitle = if (ignoring) "已允许，锁屏后系统不会因省电暂停 HaniyaX" else "允许后，锁屏时任务更不容易被系统暂停",
            value = if (ignoring) "已允许" else null,
            onClick = {
                val ok = if (ignoring) startSafely(context, Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else requestIgnoreBattery(context)
                if (!ok) toast.show("这台设备没有提供电池优化设置", icon = "info")
            },
        )
    }
}

/** Reads the 12L+ phantom-process feature flag; true when the user already turned the monitor off. */
private fun phantomMonitorDisabled(context: Context): Boolean = runCatching {
    SystemSettings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs") == "false"
}.getOrDefault(false)

private fun developerOptionsEnabled(context: Context): Boolean = runCatching {
    SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
}.getOrDefault(false)

@Composable
private fun PhantomCard() {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val sdk = Build.VERSION.SDK_INT
    val guide = remember(sdk) { BackgroundGuide.forSdk(sdk) }
    val tick = rememberResumeTick()
    val disabled = remember(tick) { sdk >= 32 && phantomMonitorDisabled(context) }
    GlassCard {
        CardHeader(
            "子进程限制", icon = "shieldAlert", caption = BackgroundGuide.androidName(sdk),
            trailing = { if (disabled) Badge("已停用", tone = BadgeTone.Accent) },
        )
        Spacer(Modifier.height(10.dp))
        if (guide.limited) {
            Text(
                "Android 12 起，系统会在后台无提示地结束 App 启动的子进程（幻影进程），终端、智能体和后台服务可能因此突然退出。",
                style = ReliefTheme.type.body, color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(if (disabled) "这台设备已经停用了这项限制。" else guide.advice, style = ReliefTheme.type.body, color = c.ink2)
        if (guide.devOptionsSwitch && !disabled) {
            Spacer(Modifier.height(14.dp))
            GlassButton("打开开发者选项", {
                if (!developerOptionsEnabled(context)) {
                    toast.show("请先在「关于手机」里连续点按版本号，开启开发者选项", icon = "info", durationMs = 4_000)
                    startSafely(context, Intent(SystemSettings.ACTION_DEVICE_INFO_SETTINGS))
                } else if (!startSafely(context, Intent(SystemSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
                    toast.show("无法打开开发者选项，请在系统设置中查找", icon = "info")
                }
            }, Modifier.fillMaxWidth(), icon = "sliders")
        }
        if (!disabled) guide.commands.forEach { cmd ->
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(cmd, style = monoStyle, color = c.ink, modifier = Modifier.weight(1f), translate = false)
                SmallRoundButton("copy", "复制命令", {
                    copyToClipboard(context, cmd, "adb")
                    toast.show("已复制命令")
                })
            }
        }
    }
}

// ---- 智能体 ----------------------------------------------------------------------------------------------------

@Composable
private fun AgentsCard(hints: Boolean) {
    GlassCard {
        SwitchRow(
            "写入 HaniyaX 插件说明", hints,
            onCheckedChange = { on -> App.settings.update { it.copy(agentSdkHints = on) } },
            subtitle = "安装智能体时在 CLAUDE.md / AGENTS.md 里加一条指向 ${Paths.GUEST_SDK} 的简短说明，方便它们开发首页卡片",
            icon = "puzzle",
        )
        Divider()
        ListRow(
            "服务商与模型", icon = "key",
            subtitle = "按智能体管理 API 地址、模型与登录方式",
            onClick = { Nav.go(Route.Providers(null)) },
        )
    }
}

// ---- 工作区 ----------------------------------------------------------------------------------------------------

@Composable
private fun WorkspaceCard(onReset: () -> Unit) {
    val c = ReliefTheme.colors
    val usage by produceState<Long?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { HostFiles.diskUsage(Paths.rootfs) }.getOrDefault(-1L) }
    }
    GlassCard {
        ListRow(
            "占用空间", icon = "db",
            subtitle = "Debian 根文件系统，包含已安装的软件和 /root 下的项目",
            value = when (val u = usage) {
                null -> "正在计算…"
                -1L -> "无法读取"
                else -> Fmt.bytes(u)
            },
            chevron = false,
        )
        Divider()
        ListRow(
            "重置工作区", icon = "trash", titleColor = c.danger,
            subtitle = "删除整个 Linux 环境后重新创建",
            onClick = onReset,
        )
    }
}

@Composable
private fun ResetSheet(visible: Boolean, onDismiss: () -> Unit) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val state by WorkspaceReset.state.collectAsState()
    var typed by remember { mutableStateOf("") }
    val busy = state is WorkspaceReset.State.Working

    GlassSheet(
        visible = visible || state !is WorkspaceReset.State.Idle,
        onDismiss = { if (state is WorkspaceReset.State.Idle) { typed = ""; focus.clearFocus(); onDismiss() } },
        title = "重置工作区",
    ) {
        when (val s = state) {
            WorkspaceReset.State.Idle -> {
                Text(
                    "将结束所有任务和服务，并删除整个 Linux 环境：已安装的软件、智能体及其登录状态、/root 下的项目和配置都会消失，无法恢复。完成后 HaniyaX 会重新启动并重新创建工作区。",
                    style = ReliefTheme.type.body, color = c.ink2,
                )
                FieldLabel("输入「${ResetConfirm.WORD}」以确认")
                GlassField(typed, { typed = it }, placeholder = ResetConfirm.WORD)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("取消", { typed = ""; focus.clearFocus(); onDismiss() }, Modifier.weight(1f))
                    GlassButton(
                        "重置并重启", {
                            focus.clearFocus()
                            WorkspaceReset.start()
                        },
                        Modifier.weight(1f), enabled = ResetConfirm.matches(typed) && !busy, contentColor = c.danger,
                    )
                }
            }
            is WorkspaceReset.State.Working -> {
                Text(s.step, style = ReliefTheme.type.bodyMedium, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("请不要关闭 HaniyaX。", style = ReliefTheme.type.caption, color = c.ink2)
            }
            is WorkspaceReset.State.Done -> {
                if (s.problem != null) {
                    Text(s.problem, style = ReliefTheme.type.body, color = c.amberInk)
                    Spacer(Modifier.height(20.dp))
                    GlassButton("重启 HaniyaX", { WorkspaceReset.restartApp(context) }, Modifier.fillMaxWidth(), icon = "refresh")
                } else {
                    Text("正在重新启动…", style = ReliefTheme.type.bodyMedium, color = c.ink)
                }
            }
        }
    }
}

// ---- 关于 ------------------------------------------------------------------------------------------------------

private data class Notice(val name: String, val license: String, val url: String)

private val NOTICES = listOf(
    Notice("Termux terminal-emulator / terminal-view", "Apache-2.0", "https://github.com/termux/termux-app"),
    Notice("proot（Termux 分支）", "GPL-2.0 · 查看源代码", "https://github.com/termux/proot"),
    Notice("talloc", "LGPL-3.0", "https://talloc.samba.org"),
    Notice("Debian", "各软件包遵循其自由软件许可证", "https://www.debian.org/legal/licenses/"),
    Notice("Haniya Relief UI", "设计规范与组件", "https://github.com/TodayYueC/haniya-relief-ui"),
    Notice("Inter 字体", "SIL OFL 1.1", "https://github.com/rsms/inter"),
    Notice("CC Switch", "MIT · 服务商预设", "https://github.com/farion1231/cc-switch"),
)

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val toast = LocalToast.current
    GlassCard {
        ListRow("HaniyaX", icon = "terminal", value = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", chevron = false)
        Divider()
        ListRow("系统", icon = "cpu", value = BackgroundGuide.androidName(Build.VERSION.SDK_INT), chevron = false)
    }
    GlassCard {
        CardHeader("开源许可", icon = "note", caption = "HaniyaX 使用了以下项目")
        Spacer(Modifier.height(6.dp))
        NOTICES.forEachIndexed { i, n ->
            if (i > 0) Divider()
            ItemRow(
                title = n.name, subtitle = n.license, translate = false,
                onClick = { if (!openUrl(context, n.url)) toast.show("没有可以打开链接的应用", icon = "info") },
                trailing = { dev.haniya.relief.icon.HIcon("link", size = 18.dp, tint = ReliefTheme.colors.ink2) },
            )
        }
    }
}
