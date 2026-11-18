package dev.haniya.x.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTest {
    private val m = ManifestParser.parse(
        """
        {"apiVersion":1,"id":"dev-server","name":"开发服务器","version":"1.2.0",
         "params":{"port":{"type":"number","default":3000}},
         "sources":{
           "web":{"type":"port","port":"{{params.port}}"},
           "log":{"type":"file","path":"/root/dev.log"},
           "git":{"type":"command","run":"git status --short","parse":"lines"},
           "cpu":{"type":"file","path":"/root/cpu.txt"}
         },
         "card":{"title":"开发服务器","subtitle":"127.0.0.1:{{params.port}}","blocks":[
           {"type":"status","source":"web","ok":"在线","fail":"离线"},
           {"type":"kv","items":[{"label":"改动","value":"{{sources.git.count}} 个文件"}]},
           {"type":"metric","label":"端口","value":"{{params.port}}"},
           {"type":"progress","label":"磁盘","value":"0.5"},
           {"type":"sparkline","source":"cpu","label":"CPU"}
         ]},
         "actions":[{"id":"logs","label":"最近日志","type":"sheet","blocks":[{"type":"log","source":"log","lines":2}]}],
         "tap":"logs"}
        """.trimIndent(),
        "dev-server",
    ).manifest!!

    @Test fun rendersLiveValues() {
        val params = Trust.effectiveParams(m, emptyMap())
        val text = PlainPreview.render(
            m, ApprovalState.Pending, params,
            mapOf(
                "web" to SourceValue(SourceValue.State.Ready, ok = true, value = "open"),
                "log" to SourceValue(SourceValue.State.Ready, ok = true, lines = listOf("a", "b", "c")),
                "git" to SourceValue.BLOCKED,
                "cpu" to SourceValue(SourceValue.State.Ready, ok = true, lines = listOf("1", "3", "2")),
            ),
        )
        assertTrue(text, text.contains("┌ 开发服务器   [dev-server 1.2.0]"))
        assertTrue(text, text.contains("│ 127.0.0.1:3000"))
        assertTrue(text, text.contains("可执行命令（需要用户在 HaniyaX 中确认）"))
        assertTrue(text, text.contains("● 在线"))
        assertTrue(text, text.contains("改动：[需要在 HaniyaX 中确认命令] 个文件"))
        assertTrue(text, text.contains("端口：3000"))
        assertTrue(text, text.contains("[██████████··········] 50%"))
        assertTrue(text, text.contains("CPU ▁█▄  最新 2"))
        assertTrue(text, text.contains("• 最近日志  (logs, sheet)  ← 点按卡片"))
        // The sheet shows only the last 2 lines.
        assertTrue(text, text.contains("│       b\n│       c\n"))
        assertTrue(text, !text.contains("│       a\n"))
    }

    @Test fun offlineStatusShowsFailLabelAndError() {
        val params = Trust.effectiveParams(m, emptyMap())
        val blocks = BlockRenderer.render(
            m.card.blocks,
            TemplateContext(params.values, m.params.mapValues { it.value.type }, mapOf("web" to SourceValue(SourceValue.State.Ready, ok = false, value = "closed", error = "连接被拒绝"))),
        )
        assertEquals(RenderedBlock.Status(false, "离线", "连接被拒绝"), blocks[0])
    }

    @Test fun sourcesUsedCollectsTemplateRefs() {
        assertEquals(setOf("web", "git", "cpu"), BlockRenderer.sourcesUsed(m.card.blocks, listOf(m.card.title, m.card.subtitle)))
    }

    @Test fun okWhenVariants() {
        val v = SourceValue(SourceValue.State.Ready, ok = true, value = "status: running 42")
        assertTrue(OkWhen.Contains("running").test(v))
        assertTrue(OkWhen.Gt(40.0).test(v.copy(value = "42")))
        assertTrue(!OkWhen.Lt(40.0).test(v.copy(value = "42")))
        assertTrue(OkWhen.Matches(Regex("run+ing")).test(v))
        assertTrue(!OkWhen.Ok.test(SourceValue.PENDING))
        assertTrue(OkWhen.NotOk.test(v.copy(ok = false)))
    }
}
