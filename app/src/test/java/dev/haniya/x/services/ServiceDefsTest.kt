package dev.haniya.x.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDefsTest {
    @Test fun parsesFullDefinition() {
        val def = ServiceDefs.parse(
            "web",
            """{"name":"web","command":"npm run dev","cwd":"/root/projects/web","port":5173,"healthPath":"/",
               "env":{"NODE_ENV":"development"},"autostart":true,"owner":"codex","description":"Vite","extra":1}""",
        ).getOrThrow()
        assertEquals("web", def.name)
        assertEquals("npm run dev", def.command)
        assertEquals("/root/projects/web", def.cwd)
        assertEquals(5173, def.port)
        assertEquals("/", def.healthPath)
        assertEquals(mapOf("NODE_ENV" to "development"), def.env)
        assertTrue(def.autostart)
        assertEquals("codex", def.owner)
    }

    @Test fun nameMayBeOmittedAndFileNameWins() {
        assertEquals("api", ServiceDefs.parse("api", """{"command":"./serve"}""").getOrThrow().name)
        assertEquals("api", ServiceDefs.parse("api", """{"name":"other","command":"./serve"}""").getOrThrow().name)
    }

    @Test fun defaultsApply() {
        val def = ServiceDefs.parse("x", """{"command":"sleep 100"}""").getOrThrow()
        assertEquals("/root", def.cwd)
        assertNull(def.port)
        assertFalse(def.autostart)
        assertEquals("user", def.owner)
        assertEquals("/root/.haniyax/logs/x.log", ServiceDefs.logPath(def))
        assertNull(ServiceDefs.address(def))
    }

    @Test fun lenientJsonIsAccepted() {
        // AppJson is lenient: hand-written files with unquoted keys still load.
        assertTrue(ServiceDefs.parse("x", """{command: "sleep 1", port: 8080}""").isSuccess)
    }

    @Test fun rejectsInvalidDefinitionsWithChineseMessages() {
        fun err(stem: String, json: String): String {
            val r = ServiceDefs.parse(stem, json)
            assertTrue("$stem should fail: $json", r.isFailure)
            return r.exceptionOrNull()!!.message!!
        }
        assertTrue(err("x", "not json at all {").startsWith("不是有效的 JSON"))
        assertEquals("应为 JSON 对象", err("x", "[1,2]"))
        assertTrue(err("x", """{"cwd":"/root"}""").startsWith("字段格式不正确"))
        assertTrue(err("x", """{"command":"a","port":"abc"}""").startsWith("字段格式不正确"))
        assertEquals("命令不能为空", err("x", """{"command":"  "}"""))
        assertTrue(err("x", """{"command":"a","port":70000}""").contains("70000"))
        assertEquals("目录必须是绝对路径", err("x", """{"command":"a","cwd":"projects"}"""))
        assertEquals("健康检查路径应以 / 开头", err("x", """{"command":"a","port":1,"healthPath":"health"}"""))
        assertEquals("日志路径必须是绝对路径", err("x", """{"command":"a","log":"x.log"}"""))
        assertNotNull(ServiceDefs.nameError("bad name"))
        assertTrue(err("bad name", """{"command":"a"}""").startsWith("服务名"))
    }

    @Test fun encodeRoundTrips() {
        val def = ServiceDef(name = "db", command = "postgres -D data", cwd = "/root/db", port = 5432, env = mapOf("A" to "1"))
        assertEquals(def, ServiceDefs.parse("db", ServiceDefs.encode(def)).getOrThrow())
    }

    @Test fun launchScriptExecsSimpleCommandsOnly() {
        assertEquals("exec npm run dev", ServiceDefs.launchScript("  npm run dev "))
        assertEquals("exec python3 -m http.server 8000", ServiceDefs.launchScript("python3 -m http.server 8000"))
        assertEquals("cd web && npm start", ServiceDefs.launchScript("cd web && npm start"))
        assertEquals("PORT=3000 node server.js", ServiceDefs.launchScript("PORT=3000 node server.js"))
        assertEquals("a | b", ServiceDefs.launchScript("a | b"))
        assertEquals("source .env; node x", ServiceDefs.launchScript("source .env; node x"))
        assertEquals("exec node x", ServiceDefs.launchScript("exec node x"))
        assertEquals("", ServiceDefs.launchScript("   "))
    }

    @Test fun labels() {
        assertEquals("运行中 · 由其他程序启动", ServiceDefs.statusLabel(ServiceStatus.Running, managed = false, lastExitCode = null))
        assertEquals("已退出 · 退出码 2", ServiceDefs.statusLabel(ServiceStatus.Crashed, managed = false, lastExitCode = 2))
        assertEquals("已退出", ServiceDefs.shortLabel(ServiceStatus.Crashed))
        assertEquals("没有响应", ServiceDefs.shortLabel(ServiceStatus.Unhealthy))
    }

    @Test fun managedAlive() {
        val def = ServiceDef(name = "a", command = "x")
        fun st(status: ServiceStatus, managed: Boolean) = ServiceState(def, status, managed, null, null, null, null)
        assertTrue(ServiceDefs.isManagedAlive(st(ServiceStatus.Starting, true)))
        assertTrue(ServiceDefs.isManagedAlive(st(ServiceStatus.Unhealthy, true)))
        assertFalse(ServiceDefs.isManagedAlive(st(ServiceStatus.Running, false)))
        assertFalse(ServiceDefs.isManagedAlive(st(ServiceStatus.Crashed, true)))
        assertFalse(ServiceDefs.isManagedAlive(st(ServiceStatus.Stopped, true)))
    }
}
