package dev.haniya.x.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {
    private val devServer = """
        {
          "apiVersion": 1,
          "id": "dev-server",
          "name": "开发服务器",
          "version": "1.0.0",
          "description": "端口状态与最近日志",
          "icon": "server",
          "params": {
            "port": {"type": "number", "label": "端口", "default": 3000},
            "log": {"type": "path", "label": "日志文件", "default": "~/projects/web/dev.log"}
          },
          "refresh": 10,
          "sources": {
            "web": {"type": "port", "port": "{{params.port}}"},
            "log": {"type": "file", "path": "{{params.log}}", "tail": 50}
          },
          "card": {
            "title": "开发服务器",
            "subtitle": "127.0.0.1:{{params.port}}",
            "blocks": [
              {"type": "status", "source": "web", "ok": "在线", "fail": "离线"}
            ]
          },
          "actions": [
            {"id": "logs", "label": "最近日志", "icon": "list", "type": "sheet",
             "blocks": [{"type": "log", "source": "log", "lines": 20}]}
          ],
          "tap": "logs"
        }
    """.trimIndent()

    @Test fun parsesValidManifest() {
        val r = ManifestParser.parse(devServer, "dev-server")
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val m = r.manifest!!
        assertEquals("dev-server", m.id)
        assertEquals(10, m.refreshSec)
        assertEquals("server", m.icon)
        assertEquals(2, m.params.size)
        assertEquals(ParamType.NUMBER, m.params["port"]!!.type)
        assertTrue(m.sources["web"] is SourceDef.Port)
        assertEquals("logs", m.tap)
        assertEquals(PluginTrust.DisplayOnly, m.trust)
        assertTrue(m.actions[0] is ActionDef.Sheet)
    }

    @Test fun idMustMatchDirectory() {
        val r = ManifestParser.parse(devServer, "other")
        assertNull(r.manifest)
        assertTrue(r.errors.any { it.startsWith("id：") && it.contains("目录名") })
        assertEquals("dev-server", r.rawId)
        assertEquals("开发服务器", r.rawName)
    }

    @Test fun collectsAllErrorsWithPaths() {
        val bad = """
            {"apiVersion": 2, "id": "Bad_ID", "name": "", "version": "1.0",
             "refresh": 2, "tap": "nope", "colour": "red",
             "params": {"p": {"type": "int"}, "q": {"type": "number", "default": "x"}},
             "sources": {
               "a": {"type": "port", "port": 70000},
               "b": {"type": "http", "url": "https://example.com/"},
               "c": {"type": "weird"},
               "d": {"type": "command", "run": "echo {{sources.a.value}}"}
             },
             "card": {"blocks": [
               {"type": "status", "source": "zzz"},
               {"type": "text", "value": "{{params.missing}}"},
               {"type": "log", "source": "a", "lines": 999},
               {"type": "gauge"}
             ]},
             "actions": [
               {"id": "x", "label": "X", "type": "openUrl", "url": "http://10.0.0.1/"},
               {"id": "x", "label": "Y", "type": "service", "name": "web", "op": "kill"}
             ]}
        """.trimIndent()
        val r = ManifestParser.parse(bad, "bad-id")
        assertNull(r.manifest)
        val e = r.errors.joinToString("\n")
        val expected = listOf(
            "apiVersion：", "id：", "name：", "version：", "refresh：", "tap：", "colour：",
            "params.p.type：", "params.q.default：", "sources.a.port：", "sources.b.url：", "sources.c.type：",
            "sources.d.run：", "card.blocks[0].source：", "card.blocks[1].value：", "card.blocks[2].lines：",
            "card.blocks[3].type：", "actions[0].url：", "actions[1].id：", "actions[1].op：",
        )
        for (p in expected) assertTrue("missing error for $p in\n$e", r.errors.any { it.startsWith(p) })
        assertTrue(e.contains("需要更新版本的 HaniyaX"))
    }

    @Test fun invalidJsonIsReported() {
        val r = ManifestParser.parse("{ not json", null)
        assertNull(r.manifest)
        assertTrue(r.errors.single().startsWith("plugin.json 不是有效的 JSON"))
    }

    @Test fun unknownIconFallsBackToPuzzle() {
        val r = ManifestParser.parse(devServer.replace("\"server\"", "\"rocket-ship\""), "dev-server")
        assertNotNull(r.manifest)
        assertEquals("puzzle", r.manifest!!.icon)
        assertTrue(r.warnings.any { it.contains("rocket-ship") })
    }

    @Test fun xPrefixedFieldsAreAllowed() {
        val r = ManifestParser.parse(devServer.replace("\"apiVersion\": 1,", "\"apiVersion\": 1, \"x-author\": \"me\", \"\$schema\": \"/opt/haniyax/docs/plugin.schema.json\","), "dev-server")
        assertTrue(r.errors.toString(), r.errors.isEmpty())
    }

    @Test fun okWhenParsing() {
        assertEquals(OkWhen.Ok, OkWhen.parse("ok"))
        assertEquals(OkWhen.Contains("up"), OkWhen.parse("contains:up"))
        assertEquals(OkWhen.Gt(3.0), OkWhen.parse("gt:3"))
        assertNull(OkWhen.parse("whatever"))
        assertNull(OkWhen.parse("matches:(["))
    }

    @Test fun httpMustBeLoopback() {
        assertTrue(ManifestParser.isLoopbackUrl("http://127.0.0.1:{{params.port}}/health"))
        assertTrue(ManifestParser.isLoopbackUrl("http://localhost/"))
        assertTrue(ManifestParser.isLoopbackUrl("http://localhost"))
        assertTrue(!ManifestParser.isLoopbackUrl("http://localhost.evil.com/"))
        assertTrue(!ManifestParser.isLoopbackUrl("http://127.0.0.1.nip.io/"))
        assertTrue(!ManifestParser.isLoopbackUrl("{{params.url}}"))
        assertTrue(!ManifestParser.isLoopbackUrl("https://127.0.0.1/"))
    }
}
