package dev.haniya.x.control

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Minimal HTTP/1.1 for the local control API: request line + headers + Content-Length body.
 * No chunked uploads, no keep-alive (every response closes the connection). Pure JVM code (unit tested).
 */
data class HttpRequest(
    val method: String,
    /** Decoded path without query, e.g. /v1/plugins/dev-server. */
    val path: String,
    val query: Map<String, String>,
    /** Lower-cased header names. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String) = headers[name.lowercase()]
    val bodyText get() = String(body, Charsets.UTF_8)
    /** Path segments, e.g. ["v1", "plugins", "dev-server"]. */
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }
}

class HttpException(val status: Int, message: String) : IOException(message)

data class HttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, text: String) = HttpResponse(status, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }
}

object Http {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 512 * 1024

    private val REASONS = mapOf(
        200 to "OK", 201 to "Created", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized",
        403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict",
        411 to "Length Required", 413 to "Payload Too Large", 422 to "Unprocessable Entity",
        431 to "Request Header Fields Too Large", 500 to "Internal Server Error", 501 to "Not Implemented",
        503 to "Service Unavailable",
    )

    fun reason(status: Int) = REASONS[status] ?: "Status"

    /** Reads one request. Throws [HttpException] with the status to answer for malformed input. */
    fun read(input: InputStream, maxBody: Int = MAX_BODY_BYTES): HttpRequest {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val reqLine = lines.first()
        val parts = reqLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpException(400, "请求行无效")
        val method = parts[0]
        if (!method.all { it in 'A'..'Z' }) throw HttpException(400, "请求方法无效")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpException(400, "请求路径无效")
        val headers = LinkedHashMap<String, String>()
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val c = l.indexOf(':')
            if (c <= 0) throw HttpException(400, "请求头无效")
            val k = l.substring(0, c).trim().lowercase()
            if (k.any { it.isWhitespace() }) throw HttpException(400, "请求头无效")
            val v = l.substring(c + 1).trim()
            headers[k] = if (headers.containsKey(k)) headers[k] + ", " + v else v
        }
        if (headers["transfer-encoding"] != null) throw HttpException(501, "不支持分块传输，请使用 Content-Length")
        val len = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpException(400, "Content-Length 无效") } ?: 0L
        if (len < 0) throw HttpException(400, "Content-Length 无效")
        if (len > maxBody) throw HttpException(413, "请求体过大（上限 ${maxBody / 1024} KB）")
        val body = ByteArray(len.toInt())
        var off = 0
        while (off < body.size) {
            val n = input.read(body, off, body.size - off)
            if (n < 0) throw HttpException(400, "请求体不完整")
            off += n
        }
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val path = decode(rawPath)
        if (path.split('/').any { it == ".." }) throw HttpException(400, "请求路径无效")
        return HttpRequest(method, path, query, headers, body)
    }

    private fun readHead(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0 // matches \r\n\r\n
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) throw HttpException(400, "空请求")
                throw HttpException(400, "请求头不完整")
            }
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw HttpException(431, "请求头过大")
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val s = buf.toString(Charsets.ISO_8859_1.name())
        return s.substring(0, s.length - 4)
    }

    fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        if (i < 0) decode(it) to "" else decode(it.substring(0, i)) to decode(it.substring(i + 1))
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) { throw HttpException(400, "URL 编码无效") }

    fun write(out: OutputStream, r: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            append("Content-Type: ").append(r.contentType).append("\r\n")
            append("Content-Length: ").append(r.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    /** Only loopback Host headers (with or without port) are accepted — blocks DNS-rebinding from browsers. */
    fun isAllowedHost(host: String?, port: Int): Boolean {
        if (host == null) return false
        val h = host.trim().lowercase()
        return h in setOf("127.0.0.1", "localhost", "[::1]", "127.0.0.1:$port", "localhost:$port", "[::1]:$port")
    }

    /** Constant-time check of `Authorization: Bearer <token>`. */
    fun isAuthorized(authorization: String?, token: String): Boolean {
        if (authorization == null) return false
        val a = authorization.trim()
        if (!a.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
        val given = a.substring(7).trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(given, token.toByteArray(Charsets.UTF_8))
    }
}
