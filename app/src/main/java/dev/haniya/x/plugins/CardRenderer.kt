package dev.haniya.x.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassCard
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.ProgressBar
import dev.haniya.relief.component.Sparkline
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.TextLink
import dev.haniya.relief.component.gradientSurface
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.NumStyles
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route
import kotlinx.coroutines.delay

/** Monospace style for logs and command text inside cards and sheets. */
internal val monoStyle: TextStyle
    @Composable get() = ReliefTheme.type.body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

/**
 * The home card for plugin [id]: GlassCard + header (icon, title, subtitle, trust marker) + live blocks +
 * action buttons. Data sources are evaluated on IO every `refresh` seconds, only while the card is
 * composed and the screen is at least STARTED.
 */
@Composable
internal fun PluginCard(host: PluginHost, id: String, modifier: Modifier = Modifier) {
    val loaded by host.loaded.collectAsState()
    val ready by host.firstLoadDone.collectAsState()
    val lp = loaded[id]
    when {
        lp == null -> PlaceholderCard(id, if (ready) "找不到这张卡片，可能已被移除" else "正在读取卡片…", modifier)
        lp.manifest == null -> ErrorCard(lp, modifier)
        else -> LiveCard(host, lp, lp.manifest!!, modifier)
    }
}

@Composable
private fun PlaceholderCard(id: String, text: String, modifier: Modifier) {
    val c = ReliefTheme.colors
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile("puzzle", size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(id, style = ReliefTheme.type.h3, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                Text(text, style = ReliefTheme.type.caption, color = c.ink2)
            }
        }
    }
}

/** Invalid plugin: name + first errors + 查看详情. Nothing runs. */
@Composable
private fun ErrorCard(lp: LoadedPlugin, modifier: Modifier) {
    val c = ReliefTheme.colors
    GlassCard(modifier, onClick = { Nav.go(Route.Plugin(lp.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(lp.icon, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(lp.name, style = ReliefTheme.type.h3, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                Text("卡片配置有误，暂未启用", style = ReliefTheme.type.caption, color = c.ink2)
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lp.errors.take(3).forEach {
                Text("· $it", style = ReliefTheme.type.caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (lp.errors.size > 3) Text("另有 ${lp.errors.size - 3} 处问题", style = ReliefTheme.type.caption, color = c.ink2)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextLink("查看详情", onClick = { Nav.go(Route.Plugin(lp.id)) }, icon = "chevR")
        }
    }
}

/** Polls [names] of [lp] while composed and STARTED. Restarts when the plugin, its params or approval change. */
@Composable
internal fun rememberSourceValues(lp: LoadedPlugin, names: Set<String>, refreshSec: Int): Map<String, SourceValue> {
    var values by remember(lp.id) { mutableStateOf<Map<String, SourceValue>>(emptyMap()) }
    val current by rememberUpdatedState(lp)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(lp.id, lp.stamp, lp.approval, lp.hash, lp.params, names, owner) {
        if (names.isEmpty()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val fresh = SourceEvaluator.evaluate(current, names)
                values = values + fresh
                delay(refreshSec.coerceAtLeast(5) * 1000L)
            }
        }
    }
    return values
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(host: PluginHost, lp: LoadedPlugin, m: Manifest, modifier: Modifier) {
    val c = ReliefTheme.colors
    val names = remember(m) { BlockRenderer.sourcesUsed(m.card.blocks, listOf(m.card.title, m.card.subtitle)) }
    val values = rememberSourceValues(lp, names, m.refreshSec)
    val ctx = lp.templateContext(values)
    val rendered = BlockRenderer.render(m.card.blocks, ctx)
    val runner = rememberActionRunner(host, lp.id)
    val tap = m.action(m.tap)
    val title = m.card.title?.let { Templates.render(it, ctx) }?.takeIf { it.isNotBlank() } ?: m.name
    val subtitle = m.card.subtitle?.let { Templates.render(it, ctx) }?.takeIf { it.isNotBlank() }

    GlassCard(modifier, onClick = tap?.let { a -> { runner.run(a) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(m.icon, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = ReliefTheme.type.h3, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                if (subtitle != null) Text(subtitle, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
            }
            if (m.trust == PluginTrust.RunsCommands) CommandBadge()
            if (tap != null) HIcon("chevR", size = 18.dp, tint = c.ink2.copy(alpha = 0.7f))
        }
        CardNotice(lp)
        if (rendered.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RenderedBlocks(rendered)
            }
        }
        val buttons = m.actions.filter { it.id != m.tap }
        if (buttons.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                buttons.forEach { a ->
                    GlassButton(a.label, onClick = { runner.run(a) }, size = ButtonSize.Small, icon = a.icon ?: defaultIcon(a))
                }
            }
        }
    }
    runner.Sheets(lp, m)
}

internal fun defaultIcon(a: ActionDef): String = when (a) {
    is ActionDef.Sheet -> "list"
    is ActionDef.Command -> "play"
    is ActionDef.Terminal -> "terminal"
    is ActionDef.OpenUrl -> "globe"
    is ActionDef.Service -> when (a.op) { ServiceOp.START -> "play"; ServiceOp.STOP -> "stop"; ServiceOp.RESTART -> "refresh" }
}

/** Amber marker for cards that can run commands: terminal glyph + 可执行命令. */
@Composable
internal fun CommandBadge(modifier: Modifier = Modifier) {
    val c = ReliefTheme.colors
    Row(
        modifier.height(24.dp).clip(RoundedCornerShape(999.dp)).background(c.amberSoft).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HIcon("terminal", size = 14.dp, tint = c.amberInk)
        Text("可执行命令", style = ReliefTheme.type.badge, color = c.amberInk, maxLines = 1)
    }
}

/** Calm notices: approval needed / changed, params missing, local.json problems. */
@Composable
private fun CardNotice(lp: LoadedPlugin) {
    val c = ReliefTheme.colors
    val (text, button) = when {
        lp.approval == ApprovalState.Changed -> "命令已变更，需要重新确认" to "查看并确认"
        lp.approval == ApprovalState.Pending -> "这张卡片会运行命令，确认后才会启用" to "查看并允许"
        lp.params.missing.isNotEmpty() -> "请先填写参数：${lp.params.missing.joinToString("、") { n -> lp.manifest?.params?.get(n)?.label ?: n }}" to "填写参数"
        lp.localProblems.isNotEmpty() -> (lp.localProblems.first()) to "查看"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).insetSurface(RoundedCornerShape(16.dp), depth = 3.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HIcon(if (lp.approval == ApprovalState.Pending || lp.approval == ApprovalState.Changed) "shield" else "info", size = 16.dp, tint = c.ink2)
        Text(text, style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextLink(button, onClick = { Nav.go(Route.Plugin(lp.id)) })
    }
}

/** Renders display models; consecutive metrics share a row (up to three). */
@Composable
internal fun RenderedBlocks(blocks: List<RenderedBlock>) {
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        if (b is RenderedBlock.Metric) {
            val group = ArrayList<RenderedBlock.Metric>()
            while (i < blocks.size && blocks[i] is RenderedBlock.Metric && group.size < 3) { group.add(blocks[i] as RenderedBlock.Metric); i++ }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { MetricTile(it, Modifier.weight(1f)) }
            }
            continue
        }
        RenderedBlockView(b)
        i++
    }
}

@Composable
internal fun RenderedBlockView(b: RenderedBlock) {
    val c = ReliefTheme.colors
    when (b) {
        is RenderedBlock.Status -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(b.ok)
            Column(Modifier.weight(1f)) {
                Text(b.label, style = ReliefTheme.type.bodyMedium, color = if (b.ok == false) c.ink2 else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
                if (b.note != null) Text(b.note, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
            }
        }
        is RenderedBlock.Text -> {
            val style = when (b.style) {
                TextBlockStyle.BODY -> ReliefTheme.type.body
                TextBlockStyle.CAPTION -> ReliefTheme.type.caption
                TextBlockStyle.MONO -> monoStyle
            }
            Text(b.text, style = style, color = if (b.style == TextBlockStyle.CAPTION) c.ink2 else c.ink, maxLines = 12, overflow = TextOverflow.Ellipsis, translate = false)
        }
        is RenderedBlock.Kv -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.items.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(k, style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 2, translate = false)
                    Text(
                        v, style = ReliefTheme.type.body, color = c.ink, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp), translate = false,
                    )
                }
            }
        }
        is RenderedBlock.Metric -> MetricTile(b, Modifier.fillMaxWidth())
        is RenderedBlock.Log -> LogBlock(b.lines, b.note)
        is RenderedBlock.Progress -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(b.label ?: "", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 1, translate = false)
                Text(b.fraction?.let { "${(it * 100).toInt()}%" } ?: b.text, style = NumStyles.caption, color = c.ink2, maxLines = 1)
            }
            ProgressBar(b.fraction ?: 0f)
        }
        is RenderedBlock.Sparkline -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(b.label ?: "", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 1, translate = false)
                if (b.values.isNotEmpty()) Text(PlainPreview.fmt(b.values.last()), style = NumStyles.caption, color = c.ink2)
            }
            if (b.values.size >= 2) Sparkline(b.values, Modifier.fillMaxWidth().height(44.dp))
            if (b.note != null) Text(b.note, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean?) {
    val c = ReliefTheme.colors
    when (ok) {
        true -> Box(Modifier.size(12.dp).gradientSurface(CircleShape, glow = 4.dp))
        false -> Box(Modifier.size(12.dp).insetSurface(CircleShape, depth = 2.dp))
        null -> Box(Modifier.size(12.dp).clip(CircleShape).background(c.track))
    }
}

@Composable
private fun MetricTile(b: RenderedBlock.Metric, modifier: Modifier) {
    val c = ReliefTheme.colors
    Column(modifier.insetSurface(RoundedCornerShape(16.dp), depth = 3.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(b.label, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(b.value.ifBlank { "—" }, style = NumStyles.xs, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            if (b.unit != null) Text(" ${b.unit}", style = ReliefTheme.type.caption, color = c.ink2, modifier = Modifier.padding(bottom = 2.dp), translate = false)
        }
    }
}

/** Inset monospace block; long lines scroll sideways instead of wrapping. */
@Composable
internal fun LogBlock(lines: List<String>, note: String?, modifier: Modifier = Modifier) {
    val c = ReliefTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (note != null) Text(note, style = ReliefTheme.type.caption, color = c.ink2, translate = false)
        if (lines.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).insetSurface(RoundedCornerShape(16.dp), depth = 3.dp)) {
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    lines.forEach { l ->
                        Text(l.ifEmpty { " " }, style = monoStyle, color = c.ink, maxLines = 1, softWrap = false, translate = false)
                    }
                }
            }
        }
    }
}

/** Header weight used by sheets. */
internal val sheetLabel: TextStyle
    @Composable get() = ReliefTheme.type.label.copy(fontWeight = FontWeight.Medium)
