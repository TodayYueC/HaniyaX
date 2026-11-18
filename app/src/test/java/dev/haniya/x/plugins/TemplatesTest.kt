package dev.haniya.x.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TemplatesTest {
    private val types = mapOf("name" to ParamType.STRING, "port" to ParamType.NUMBER, "dir" to ParamType.PATH, "on" to ParamType.BOOLEAN)

    @Test fun rendersParamsAndSources() {
        val ctx = TemplateContext(
            params = mapOf("name" to JsonPrimitive("web"), "port" to JsonPrimitive(3000)),
            paramTypes = types,
            sources = mapOf(
                "web" to SourceValue(SourceValue.State.Ready, ok = true, value = "open"),
                "api" to SourceValue(SourceValue.State.Ready, ok = true, value = "{}", json = Json.parseToJsonElement("""{"data":{"items":[{"n":"a"},{"n":"b"}]},"count":2}""")),
                "log" to SourceValue(SourceValue.State.Ready, ok = true, lines = listOf("x", "y")),
            ),
        )
        assertEquals("web on 3000: open true", Templates.render("{{params.name}} on {{ params.port }}: {{sources.web}} {{sources.web.ok}}", ctx))
        assertEquals("b", Templates.render("{{sources.api.json.data.items[1].n}}", ctx))
        assertEquals("a", Templates.render("{{sources.api.json.data.items.0.n}}", ctx))
        assertEquals("2", Templates.render("{{sources.api.json.count}}", ctx))
        assertEquals("x\ny|2", Templates.render("{{sources.log.lines}}|{{sources.log.count}}", ctx))
        assertEquals("{{ literal", Templates.render("{{'{{'}} literal", ctx))
    }

    @Test fun pendingAndBlockedSources() {
        val ctx = TemplateContext(sources = mapOf("a" to SourceValue.PENDING, "b" to SourceValue.BLOCKED))
        assertEquals("…", Templates.render("{{sources.a}}", ctx))
        assertEquals(SourceValue.BLOCKED_TEXT, Templates.render("{{sources.b.stdout}}", ctx))
    }

    @Test fun shellQuotesParams() {
        val ctx = TemplateContext(
            params = mapOf(
                "name" to JsonPrimitive("it's; rm -rf / \$(whoami) `id`"),
                "port" to JsonPrimitive(8080),
                "dir" to JsonPrimitive("~/projects/my app"),
                "on" to JsonPrimitive(true),
            ),
            paramTypes = types,
        )
        assertEquals("echo 'it'\\''s; rm -rf / \$(whoami) `id`'", Templates.render("echo {{params.name}}", ctx, shell = true))
        assertEquals("curl localhost:8080", Templates.render("curl localhost:{{params.port}}", ctx, shell = true))
        assertEquals("cd \"\$HOME\"'/projects/my app'", Templates.render("cd {{params.dir}}", ctx, shell = true))
        assertEquals("x=true", Templates.render("x={{params.on}}", ctx, shell = true))
        // Non-shell rendering expands ~ for paths and leaves strings as-is.
        assertEquals("/root/projects/my app", Templates.render("{{params.dir}}", ctx))
        // Missing params become an empty quoted word in commands.
        assertEquals("echo ''", Templates.render("echo {{params.nothing}}", TemplateContext(), shell = true))
    }

    @Test fun quoteIsRoundTripSafe() {
        for (s in listOf("", "a b", "'", "''", "a'b'c", "\n", "\$HOME", "\"q\"")) {
            val q = ShellQuote.quote(s)
            assertTrue(q.startsWith("'") && q.endsWith("'"))
            // Re-parse POSIX single-quote syntax to check it is the identity.
            val sb = StringBuilder(); var i = 0; var inq = false
            while (i < q.length) {
                val c = q[i]
                if (inq) { if (c == '\'') inq = false else sb.append(c) }
                else if (c == '\'') inq = true
                else if (c == '\\') { sb.append(q[i + 1]); i++ }
                i++
            }
            assertEquals(s, sb.toString())
        }
    }

    @Test fun malformedTemplatesThrow() {
        for (t in listOf("{{params.}}", "{{params.a.b}}", "{{foo.bar}}", "{{sources.x.nope}}", "{{sources.x.value.y}}", "unclosed {{params.a", "{{}}")) {
            try { Templates.parse(t); fail("expected error for $t") } catch (_: Templates.TemplateError) {}
        }
    }

    @Test fun jsonPathParsing() {
        assertEquals(listOf("a", 0, "b"), JsonPath.parse("a[0].b"))
        assertEquals(listOf("a", 1, 2), JsonPath.parse("a[1][2]"))
        assertEquals(null, JsonPath.parse("a..b"))
        assertEquals(null, JsonPath.parse("a[x]"))
    }

    @Test fun numbersAndFractions() {
        assertEquals(0.42f, Numbers.fraction("0.42")!!, 1e-6f)
        assertEquals(0.42f, Numbers.fraction("42%")!!, 1e-6f)
        assertEquals(0.5f, Numbers.fraction("5/10")!!, 1e-6f)
        assertEquals(0.73f, Numbers.fraction("73")!!, 1e-6f)
        assertEquals(null, Numbers.fraction("n/a"))
        assertEquals(listOf(1f, 2.5f, 3f), Numbers.series(listOf("t1 1", "t2 2.5", "junk", "3")))
    }
}
