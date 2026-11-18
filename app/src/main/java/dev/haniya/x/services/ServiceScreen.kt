package dev.haniya.x.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.haniya.relief.component.Badge
import dev.haniya.relief.component.BadgeTone
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.EmptyState
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.HChip
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Radii
import dev.haniya.relief.component.SmallRoundButton
import dev.haniya.relief.component.SwitchRow
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.home.ConfirmSheet
import dev.haniya.x.home.Fmt
import dev.haniya.x.home.InfoRow
import dev.haniya.x.home.copyToClipboard
import dev.haniya.x.home.monoStyle
import dev.haniya.x.home.openUrl
import dev.haniya.x.home.rememberNow
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val LOG_LINES = 200
private const val SHORT_LINES = 20
private const val LOG_REFRESH_MS = 2_000L

/** Service detail: status, address, start / stop / restart, live log tail, definition, delete. */
@Composable
fun ServiceScreen(name: String) {
    val c = ReliefTheme.colors
    val toast = LocalToast.current
    val services by App.services.services.collectAsState()
    val errors by remember { ServiceManager.errorsOf(App.services) }.collectAsState()
    val s = services.firstOrNull { it.def.name == name }
    var confirmDelete by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(true) }
    LaunchedEffect(name) {
        App.services.reload()
        delay(3_500)
        waiting = false
    }

    Page(
        title = name,
        caption = s?.def?.description ?: "后台服务",
        overlay = {
            ConfirmSheet(
                visible = confirmDelete,
                title = "删除服务 $name？",
                message = "会先停止它，再删除 ~/.haniyax/services/$name.json。日志和项目文件都会保留。",
                confirmText = "删除服务",
                onConfirm = {
                    confirmDelete = false
                    App.services.remove(name)
                    toast.show("已删除服务 $name")
                    Nav.back()
                },
                onDismiss = { confirmDelete = false },
            )
        },
    ) {
        if (s == null) {
            GlassCard {
                if (waiting) {
                    Text("正在读取服务…", style = ReliefTheme.type.body, color = c.ink2)
                } else {
                    EmptyState(
                        "找不到这个服务", subtitle = "它的定义文件可能已被移除或无法读取", icon = "server",
                        action = "返回", onAction = { Nav.back() },
                    )
                }
            }
            return@Page
        }
        StatusCard(s, errors[name])
        LogCard(s)
        DefinitionCard(s)
        GlassButton(
            "删除服务", { confirmDelete = true }, Modifier.fillMaxWidth(),
            icon = "trash", contentColor = c.danger,
        )
    }
}

@Composable
private fun StatusCard(s: ServiceState, error: String?) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val name = s.def.name
    val tone = when (s.status) {
        ServiceStatus.Running -> BadgeTone.Accent
        ServiceStatus.Starting -> BadgeTone.Acc2
        ServiceStatus.Unhealthy, ServiceStatus.Crashed -> BadgeTone.Amber
        ServiceStatus.Stopped -> BadgeTone.Neutral
    }
    val now = rememberNow(1_000)
    GlassCard {
        CardHeader("状态", icon = "server", trailing = { Badge(ServiceDefs.shortLabel(s.status), tone = tone) })
        Spacer(Modifier.height(8.dp))
        val address = s.address
        if (address != null) {
            InfoRow("地址", address, mono = true, trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallRoundButton("copy", "复制地址", {
                        copyToClipboard(context, address)
                        toast.show("已复制地址")
                    })
                    SmallRoundButton("globe", "在浏览器中打开", {
                        val path = s.def.healthPath ?: "/"
                        if (!openUrl(context, "http://$address$path")) toast.show("没有可以打开链接的应用", icon = "info")
                    })
                }
            })
            Divider()
        }
        InfoRow(
            "管理方式",
            when {
                s.managed -> "由 HaniyaX 启动"
                s.status == ServiceStatus.Running || s.status == ServiceStatus.Unhealthy -> "由其他程序启动，需在启动它的地方结束"
                else -> "由 HaniyaX 管理"
            },
        )
        val pid = s.pid
        if (pid != null) {
            Divider()
            InfoRow("进程", "pid $pid", mono = true)
        }
        val started = s.startedAt
        if (started != null) {
            Divider()
            InfoRow("已运行", Fmt.duration(now - started))
        }
        val code = s.lastExitCode
        if (code != null && !ServiceDefs.isManagedAlive(s)) {
            Divider()
            InfoRow("上次退出", "退出码 $code")
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                HIcon("info", size = 18.dp, tint = c.amberInk)
                Text(error, style = ReliefTheme.type.body, color = c.amberInk, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            ServiceDefs.isManagedAlive(s) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("停止", { App.services.stop(name) }, Modifier.weight(1f), icon = "stop")
                GlassButton("重启", { App.services.restart(name) }, Modifier.weight(1f), icon = "refresh")
            }
            s.status == ServiceStatus.Running || s.status == ServiceStatus.Unhealthy ->
                Text(
                    "端口已被其他程序占用，HaniyaX 不会重复启动。",
                    style = ReliefTheme.type.caption, color = c.ink2,
                )
            else -> PrimaryButton("启动", { App.services.start(name) }, Modifier.fillMaxWidth(), icon = "play")
        }
    }
}

@Composable
private fun LogCard(s: ServiceState) {
    val c = ReliefTheme.colors
    val context = LocalContext.current
    val toast = LocalToast.current
    val name = s.def.name
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lines by remember(name) { mutableStateOf<List<String>?>(null) }
    var shortOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(name, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                lines = App.services.tail(name, LOG_LINES)
                delay(LOG_REFRESH_MS)
            }
        }
    }

    val all = lines
    val shown = if (all == null) null else if (shortOnly) all.takeLast(SHORT_LINES) else all
    GlassCard {
        CardHeader(
            "日志", icon = "note",
            caption = Fmt.shortPath(ServiceDefs.logPath(s.def)),
            trailing = {
                SmallRoundButton("copy", "复制日志", {
                    val text = shown?.joinToString("\n").orEmpty()
                    if (text.isEmpty()) toast.show("还没有可复制的日志", icon = "info")
                    else {
                        copyToClipboard(context, text, "$name log")
                        toast.show("已复制 ${shown?.size ?: 0} 行日志")
                    }
                })
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HChip("最近 $LOG_LINES 行", !shortOnly, { shortOnly = false })
            HChip("仅显示最近 $SHORT_LINES 行", shortOnly, { shortOnly = true })
        }
        Spacer(Modifier.height(14.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(shown?.size, shown?.lastOrNull(), scroll.maxValue) {
            // Follow the end of the log, like `tail -f`.
            scroll.scrollTo(scroll.maxValue)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 420.dp)
                .insetSurface(RoundedCornerShape(Radii.sm), depth = 3.dp)
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            when {
                shown == null -> Text("正在读取…", style = ReliefTheme.type.caption, color = c.ink2)
                shown.isEmpty() -> Text("还没有日志输出", style = ReliefTheme.type.caption, color = c.ink2)
                else -> Text(shown.joinToString("\n"), style = monoStyle, color = c.ink, translate = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("每 2 秒刷新", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun DefinitionCard(s: ServiceState) {
    val toast = LocalToast.current
    val def = s.def
    GlassCard {
        CardHeader(
            "定义", icon = "list",
            caption = if (def.owner == "user") "~/.haniyax/services/${def.name}.json" else "由 ${def.owner} 登记 · ~/.haniyax/services/${def.name}.json",
        )
        Spacer(Modifier.height(8.dp))
        InfoRow("命令", def.command, mono = true)
        Divider()
        InfoRow("目录", Fmt.shortPath(def.cwd), mono = true)
        Divider()
        InfoRow("端口", def.port?.toString() ?: "未声明")
        if (def.healthPath != null) {
            Divider()
            InfoRow("健康检查", "GET ${def.healthPath}", mono = true)
        }
        if (def.env.isNotEmpty()) {
            Divider()
            // Values may be secrets: only the names are shown.
            InfoRow("环境变量", def.env.keys.sorted().joinToString(", "), mono = true)
        }
        Divider()
        SwitchRow(
            "随工作区启动", def.autostart,
            onCheckedChange = { on ->
                try {
                    App.services.define(def.copy(autostart = on))
                } catch (e: Exception) {
                    toast.show("无法保存：${e.message ?: "未知错误"}", icon = "warn")
                }
            },
            subtitle = "打开 HaniyaX 并就绪后自动启动",
        )
    }
}
