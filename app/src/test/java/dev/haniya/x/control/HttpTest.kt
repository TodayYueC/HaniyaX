package dev.haniya.x.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpTest {
    private fun req(s: String) = Http.read(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))

    @Test fun parsesGet() {
        val r = req("GET /v1/plugins/dev-server/preview?x=1&y=a%20b HTTP/1.1\r\nHost: 127.0.0.1:4567\r\nAuthorization: Bearer abc\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/v1/plugins/dev-server/preview", r.path)
        assertEquals(listOf("v1", "plugins", "dev-server", "preview"), r.segments)
        assertEquals(mapOf("x" to "1", "y" to "a b"), r.query)
        assertEquals("127.0.0.1:4567", r.header("Host"))
        assertEquals(0, r.body.size)
    }

    @Test fun parsesPostBody() {
        val body = "{\"a\":\"中文\"}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = req("POST /v1/plugins/x/validate HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/json\r\n\r\n$body")
        assertEquals(body, r.bodyText)
    }

    @Test fun rejectsMalformed() {
        val cases = mapOf(
            "" to 400,
            "GARBAGE\r\n\r\n" to 400,
            "GET nopath HTTP/1.1\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nBadHeader\r\n\r\n" to 400,
            "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc" to 400,
            "POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n" to 413,
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" to 501,
            "GET /v1/%2e%2e/secret HTTP/1.1\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\nX: ${"a".repeat(Http.MAX_HEADER_BYTES)}\r\n\r\n" to 431,
        )
        for ((raw, status) in cases) {
            try { req(raw); fail("expected $status for ${raw.take(40)}") } catch (e: HttpException) { assertEquals(raw.take(40), status, e.status) }
        }
    }

    @Test fun hostAndAuth() {
        assertTrue(Http.isAllowedHost("127.0.0.1:4000", 4000))
        assertTrue(Http.isAllowedHost("localhost", 4000))
        assertFalse(Http.isAllowedHost("evil.com", 4000))
        assertFalse(Http.isAllowedHost("127.0.0.1:4001", 4000))
        assertFalse(Http.isAllowedHost(null, 4000))
        assertTrue(Http.isAuthorized("Bearer tok", "tok"))
        assertTrue(Http.isAuthorized("bearer tok", "tok"))
        assertFalse(Http.isAuthorized("Bearer tok2", "tok"))
        assertFalse(Http.isAuthorized("Basic tok", "tok"))
        assertFalse(Http.isAuthorized(null, "tok"))
    }

    @Test fun writesResponse() {
        val bo = ByteArrayOutputStream()
        Http.write(bo, HttpResponse.json(404, "{\"error\":\"没有\"}"))
        val s = bo.toString("UTF-8")
        assertTrue(s.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(s.contains("Content-Length: ${"{\"error\":\"没有\"}".toByteArray().size}\r\n"))
        assertTrue(s.endsWith("\r\n\r\n{\"error\":\"没有\"}"))
    }
}
