package dev.haniya.x.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.CardHeader
import dev.haniya.relief.component.CheckCircle
import dev.haniya.relief.component.Divider
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.ListRow
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.SwitchRow
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.tr
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.App
import dev.haniya.x.home.SectionLabel
import dev.haniya.x.home.monoStyle
import dev.haniya.x.ui.Page
import java.text.DateFormat
import java.util.Date

internal val HostMode.label: String
    get() = when (this) {
        HostMode.Disabled -> "已禁用"
        HostMode.Normal -> "普通访问"
        HostMode.Root -> "Root 访问"
    }

private val HostPolicy.label: String
    get() = when (this) {
        HostPolicy.Manual -> "手动确认"
        HostPolicy.Routine -> "常规操作自动授权"
        HostPolicy.Full -> "完全授权"
    }

private val HostPolicy.description: String
    get() = when (this) {
        HostPolicy.Manual -> "每次操作都需要在手机上确认。"
        HostPolicy.Routine -> "自动允许普通身份查询属性、应用列表和系统设置。"
        HostPolicy.Full -> "自动执行当前访问模式允许的所有操作，无需逐次确认。"
    }

private val HostRoot.label: String
    get() = when (this) {
        HostRoot.Unchecked -> "尚未检测"
        HostRoot.Missing -> "未检测到可用的 su"
        HostRoot.Detected -> "已检测到 su，等待授权验证"
        HostRoot.Checking -> "正在验证 Root 授权…"
        HostRoot.Granted -> "已验证 Android UID 0"
        HostRoot.Denied -> "未取得 Android UID 0"
    }

@Composable
fun HostAccessScreen() {
    val host = App.hostBridge
    val access by host.access.collectAsState()
    val history by host.records.collectAsState()
    val active by host.activeSessions.collectAsState()
    var fullPrompt by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    Page(
        title = "Android 宿主访问",
        overlay = {
            GlassSheet(fullPrompt, onDismiss = { fullPrompt = false }, title = "完全授权") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(HostPolicy.Full.description, style = ReliefTheme.type.body)
                    Text("在 Root 访问下，工作区程序可以直接执行 Root 命令和修改系统设置。", style = ReliefTheme.type.caption)
                    PrimaryButton("启用完全授权", {
                        host.setPolicy(HostPolicy.Full)
                        fullPrompt = false
                    }, Modifier.fillMaxWidth())
                }
            }
        },
    ) {
        GlassCard {
            SwitchRow(
                title = "启用宿主访问", checked = access.mode != HostMode.Disabled,
                onCheckedChange = { if (it) host.enableNormal() else host.disable() },
                icon = "shield", enabled = access.available,
                subtitle = if (!access.available) "宿主服务暂不可用" else "允许 Linux 工作区向 Android 发送操作请求。每次重启 HaniyaX 后需重新开启。",
            )
            Divider()
            ListRow("访问模式", value = tr(access.mode.label))
            ListRow("Android 应用 UID", value = access.uid.toString())
            ListRow("Root 授权", value = tr(access.root.label))
            if (access.mode == HostMode.Normal && access.root != HostRoot.Checking && access.root != HostRoot.Missing) {
                Divider()
                ListRow("检测并启用 Root 访问", subtitle = "可能需要在系统 Root 管理器中授权。", onClick = host::enableRoot)
            }
            if (access.mode == HostMode.Root) {
                Divider()
                ListRow("切换为普通访问", onClick = host::enableNormal)
            }
            if (access.mode != HostMode.Disabled) {
                Divider()
                ListRow("紧急关闭宿主访问", titleColor = ReliefTheme.colors.amberInk,
                    subtitle = "撤销权限并结束正在处理的宿主请求。", onClick = host::disable)
            }
        }

        SectionLabel("授权策略")
        GlassCard {
            HostPolicy.entries.forEachIndexed { index, policy ->
                if (index != 0) Divider()
                ListRow(
                    title = policy.label, subtitle = policy.description,
                    onClick = { if (policy == HostPolicy.Full && access.policy != policy) fullPrompt = true else host.setPolicy(policy) },
                    trailing = { CheckCircle(access.policy == policy, onClick = null, size = 24.dp, label = policy.label) },
                )
            }
        }
        if (active.isNotEmpty()) {
            GlassCard {
                CardHeader("进行中的宿主请求")
                active.forEach { session ->
                    ListRow(
                        title = session.operation, subtitle = if (session.root) "Root 访问" else "普通访问", chevron = false,
                        trailing = { GlassButton("终止", { host.cancel(session.id) }) },
                    )
                }
            }
        }
        GlassCard {
            ListRow("执行记录", value = history.size.toString(), onClick = { showHistory = !showHistory })
            if (showHistory && history.isEmpty()) Text("暂无执行记录", style = ReliefTheme.type.caption)
            if (showHistory) history.forEach { record ->
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(record.at))
                val uid = tr(if (record.root) "Root 访问" else "普通访问")
                val result = tr(when (record.result) {
                    "finished" -> "已结束"
                    "denied" -> "已拒绝"
                    "approval-expired" -> "确认超时"
                    "timeout", "launch-timeout" -> "超时"
                    "permission-changed" -> "已撤销"
                    "canceled", "disconnected" -> "已取消"
                    else -> "失败"
                })
                Divider()
                Text("${record.command} · $uid · $result (${record.exit})", style = ReliefTheme.type.bodyMedium, translate = false,
                    modifier = Modifier.padding(top = 8.dp))
                Text(date, style = ReliefTheme.type.caption, translate = false, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        GlassCard {
            CardHeader("Linux 中的用法", icon = "terminal")
            SelectionContainer {
                Text("haniyax host status\nhaniyax host --help", style = monoStyle, translate = false,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun HostApprovalSheet() {
    val queue by App.hostBridge.approvals.collectAsState()
    val request = queue.firstOrNull() ?: return
    GlassSheet(
        visible = true,
        onDismiss = { App.hostBridge.decide(request.id, false) },
        title = "Android 宿主请求等待确认",
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (request.root) "此请求以 Android UID 0 执行。" else "此请求以 HaniyaX 的 Android 应用身份执行。",
                style = ReliefTheme.type.bodyMedium)
            SelectionContainer {
                Text(request.command, style = monoStyle, translate = false,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("拒绝", { App.hostBridge.decide(request.id, false) }, Modifier.weight(1f))
                PrimaryButton("允许一次", { App.hostBridge.decide(request.id, true) }, Modifier.weight(1f))
            }
            GlassButton("紧急关闭宿主访问", App.hostBridge::disable, Modifier.fillMaxWidth())
        }
    }
}
