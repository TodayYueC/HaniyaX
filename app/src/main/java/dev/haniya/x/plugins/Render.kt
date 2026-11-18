package dev.haniya.x.plugins

/**
 * Turns manifest blocks + live source values into display models. Shared by the Compose card and the
 * plain-text preview used by `hx plugin preview`, so both always show the same thing. Pure JVM code.
 */
sealed interface RenderedBlock {
    /** [ok] null = not known yet. [note] = a calm explanation (error, needs approval, …). */
    data class Status(val ok: Boolean?, val label: String, val note: String?) : RenderedBlock
    data class Text(val text: String, val style: TextBlockStyle) : RenderedBlock
    data class Kv(val items: List<Pair<String, String>>) : RenderedBlock
    data class Metric(val label: String, val value: String, val unit: String?) : RenderedBlock
    data class Log(val lines: List<String>, val note: String?) : RenderedBlock
    data class Progress(val fraction: Float?, val label: String?, val text: String) : RenderedBlock
    data class Sparkline(val values: List<Float>, val label: String?, val note: String?) : RenderedBlock
}

object BlockRenderer {
    fun render(blocks: List<Block>, ctx: TemplateContext): List<RenderedBlock> = blocks.map { render(it, ctx) }

    fun render(b: Block, ctx: TemplateContext): RenderedBlock = when (b) {
        is Block.Status -> {
            val v = ctx.sources[b.source] ?: SourceValue.PENDING
            when (v.state) {
                SourceValue.State.Pending -> RenderedBlock.Status(null, "检查中…", null)
                SourceValue.State.Blocked -> RenderedBlock.Status(null, "等待确认", SourceValue.BLOCKED_TEXT)
                SourceValue.State.MissingParams -> RenderedBlock.Status(null, "未配置", SourceValue.MISSING_TEXT)
                SourceValue.State.Ready -> {
                    val ok = b.okWhen.test(v)
                    RenderedBlock.Status(ok, Templates.render(if (ok) b.ok else b.fail, ctx), if (!ok) v.error else null)
                }
            }
        }
        is Block.Text -> RenderedBlock.Text(Templates.render(b.value, ctx), b.style)
        is Block.Kv -> RenderedBlock.Kv(b.items.map { Templates.render(it.label, ctx) to Templates.render(it.value, ctx) })
        is Block.Metric -> RenderedBlock.Metric(Templates.render(b.label, ctx), Templates.render(b.value, ctx), b.unit)
        is Block.Log -> {
            val v = ctx.sources[b.source] ?: SourceValue.PENDING
            when (v.state) {
                SourceValue.State.Pending -> RenderedBlock.Log(emptyList(), "读取中…")
                SourceValue.State.Blocked -> RenderedBlock.Log(emptyList(), SourceValue.BLOCKED_TEXT)
                SourceValue.State.MissingParams -> RenderedBlock.Log(emptyList(), SourceValue.MISSING_TEXT)
                SourceValue.State.Ready -> {
                    val lines = (if (v.lines.isNotEmpty()) v.lines else SourceValue.splitLines(v.value)).takeLast(b.lines)
                    RenderedBlock.Log(lines, v.error ?: if (lines.isEmpty()) "暂无内容" else null)
                }
            }
        }
        is Block.Progress -> {
            val text = Templates.render(b.value, ctx)
            RenderedBlock.Progress(Numbers.fraction(text), b.label?.let { Templates.render(it, ctx) }, text)
        }
        is Block.Sparkline -> {
            val v = ctx.sources[b.source] ?: SourceValue.PENDING
            val label = b.label?.let { Templates.render(it, ctx) }
            when (v.state) {
                SourceValue.State.Pending -> RenderedBlock.Sparkline(emptyList(), label, "读取中…")
                SourceValue.State.Blocked -> RenderedBlock.Sparkline(emptyList(), label, SourceValue.BLOCKED_TEXT)
                SourceValue.State.MissingParams -> RenderedBlock.Sparkline(emptyList(), label, SourceValue.MISSING_TEXT)
                SourceValue.State.Ready -> {
                    val series = Numbers.series(if (v.lines.isNotEmpty()) v.lines else SourceValue.splitLines(v.value))
                    RenderedBlock.Sparkline(series, label, v.error ?: if (series.size < 2) "数据点不足" else null)
                }
            }
        }
    }

    /** Sources referenced by [blocks] (directly or through templates). */
    fun sourcesUsed(blocks: List<Block>, extra: List<String?> = emptyList()): Set<String> {
        val out = LinkedHashSet<String>()
        fun t(s: String?) { if (s != null) runCatching { Templates.refs(s) }.getOrNull()?.forEach { if (it.kind == TemplateRef.Kind.Source) out.add(it.name) } }
        extra.forEach { t(it) }
        for (b in blocks) when (b) {
            is Block.Status -> { out.add(b.source); t(b.ok); t(b.fail) }
            is Block.Text -> t(b.value)
            is Block.Kv -> b.items.forEach { t(it.label); t(it.value) }
            is Block.Metric -> { t(b.label); t(b.value) }
            is Block.Log -> out.add(b.source)
            is Block.Progress -> { t(b.value); t(b.label) }
            is Block.Sparkline -> { out.add(b.source); t(b.label) }
        }
        return out
    }
}

/** Plain-text rendering of a card (for `hx plugin preview` and the control API). */
object PlainPreview {
    fun render(
        m: Manifest,
        approval: ApprovalState,
        params: EffectiveParams,
        sources: Map<String, SourceValue>,
    ): String {
        val ctx = TemplateContext(params.values, m.params.mapValues { it.value.type }, sources)
        val sb = StringBuilder()
        val title = m.card.title?.let { Templates.render(it, ctx) }?.takeIf { it.isNotBlank() } ?: m.name
        sb.append("┌ ").append(title).append("   [").append(m.id).append(' ').append(m.version).append("]\n")
        m.card.subtitle?.let { Templates.render(it, ctx) }?.takeIf { it.isNotBlank() }?.let { sb.append("│ ").append(it).append('\n') }
        sb.append("│ 类型：").append(
            when (m.trust) {
                PluginTrust.DisplayOnly -> "仅展示"
                PluginTrust.RunsCommands -> "可执行命令（" + when (approval) {
                    ApprovalState.Approved -> "已允许"
                    ApprovalState.Changed -> "命令已变更，需要用户在 HaniyaX 中重新确认"
                    else -> "需要用户在 HaniyaX 中确认"
                } + "）"
            },
        ).append('\n')
        if (params.missing.isNotEmpty()) sb.append("│ 缺少参数：").append(params.missing.joinToString("、")).append("（hx plugin params ${m.id} 名称=值）\n")
        sb.append("├\n")
        for (b in BlockRenderer.render(m.card.blocks, ctx)) block(sb, b, "│ ")
        if (m.actions.isNotEmpty()) {
            sb.append("├ 动作\n")
            for (a in m.actions) {
                sb.append("│ • ").append(a.label).append("  (").append(a.id).append(", ").append(actionType(a)).append(')')
                if (m.tap == a.id) sb.append("  ← 点按卡片")
                sb.append('\n')
                if (a is ActionDef.Sheet) {
                    a.title?.let { Templates.render(it, ctx) }?.takeIf { it.isNotBlank() }?.let { sb.append("│     ▸ ").append(it).append('\n') }
                    for (b in BlockRenderer.render(a.blocks, ctx)) block(sb, b, "│     ")
                }
            }
        }
        sb.append("└")
        return sb.toString()
    }

    fun block(sb: StringBuilder, b: RenderedBlock, prefix: String) {
        when (b) {
            is RenderedBlock.Status -> {
                val dot = when (b.ok) { true -> "●"; false -> "○"; null -> "◌" }
                sb.append(prefix).append(dot).append(' ').append(b.label)
                if (b.note != null) sb.append("  — ").append(b.note)
                sb.append('\n')
            }
            is RenderedBlock.Text -> SourceValue.splitLines(b.text).ifEmpty { listOf("") }.forEach { sb.append(prefix).append(it).append('\n') }
            is RenderedBlock.Kv -> b.items.forEach { (k, v) -> sb.append(prefix).append(k).append("：").append(v.replace('\n', ' ')).append('\n') }
            is RenderedBlock.Metric -> sb.append(prefix).append(b.label).append("：").append(b.value).append(b.unit?.let { " $it" } ?: "").append('\n')
            is RenderedBlock.Log -> {
                if (b.note != null) sb.append(prefix).append("[日志] ").append(b.note).append('\n')
                b.lines.forEach { sb.append(prefix).append("  ").append(it).append('\n') }
            }
            is RenderedBlock.Progress -> {
                val f = b.fraction
                val bar = if (f == null) "（无法解析：${b.text}）" else {
                    val n = (f * 20).toInt().coerceIn(0, 20)
                    "[" + "█".repeat(n) + "·".repeat(20 - n) + "] " + (f * 100).toInt() + "%"
                }
                sb.append(prefix).append(b.label?.let { "$it " } ?: "").append(bar).append('\n')
            }
            is RenderedBlock.Sparkline -> {
                sb.append(prefix).append(b.label?.let { "$it " } ?: "")
                if (b.values.size >= 2) sb.append(spark(b.values)).append("  最新 ").append(fmt(b.values.last()))
                if (b.note != null) sb.append(" ").append(b.note)
                sb.append('\n')
            }
        }
    }

    fun actionType(a: ActionDef) = when (a) {
        is ActionDef.Sheet -> "sheet"
        is ActionDef.Command -> "command"
        is ActionDef.Terminal -> "terminal"
        is ActionDef.OpenUrl -> "openUrl"
        is ActionDef.Service -> "service"
    }

    private const val TICKS = "▁▂▃▄▅▆▇█"
    fun spark(v: List<Float>): String {
        val lo = v.min(); val hi = v.max()
        return v.joinToString("") { x ->
            val i = if (hi == lo) 3 else (((x - lo) / (hi - lo)) * (TICKS.length - 1)).toInt().coerceIn(0, TICKS.length - 1)
            TICKS[i].toString()
        }
    }

    fun fmt(f: Float): String = if (f == Math.floor(f.toDouble()).toFloat() && kotlin.math.abs(f) < 1e9) f.toLong().toString() else "%.2f".format(f)
}
