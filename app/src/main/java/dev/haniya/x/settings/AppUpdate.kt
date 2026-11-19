package dev.haniya.x.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.haniya.x.agents.Semver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A published HaniyaX release (GitHub Releases of the open-source repository). */
data class AppRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val sha256Url: String?,
)

/**
 * Update check against https://github.com/TodayYueC/HaniyaX/releases. The APK is downloaded into the app cache,
 * checked against the release's .sha256 file, and handed to the system installer (which asks the user to confirm).
 */
object AppUpdate {
    const val REPO = "TodayYueC/HaniyaX"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    val releasesPage = "https://github.com/$REPO/releases"

    /** Parses the GitHub "latest release" JSON; picks the arm64 APK and its checksum. Pure, unit-tested. */
    fun parse(body: String): AppRelease? {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tag = o.str("tag_name") ?: return null
        val version = tag.removePrefix("v").trim()
        if (Semver.parse(version) == null) return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it.str("name")?.endsWith("-arm64.apk") == true }
            ?: assets.firstOrNull { it.str("name")?.endsWith(".apk") == true }
        val apkName = apk?.str("name")
        val sum = assets.firstOrNull { apkName != null && it.str("name") == "$apkName.sha256" }
        return AppRelease(
            version = version,
            notes = o.str("body").orEmpty().trim(),
            pageUrl = o.str("html_url") ?: releasesPage,
            apkUrl = apk?.str("browser_download_url"),
            apkSize = (apk?.get("size") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            sha256Url = sum?.str("browser_download_url"),
        )
    }

    fun isNewer(release: AppRelease, current: String): Boolean = Semver.isNewer(release.version, current.substringBefore('-'))

    /** Release notes are bilingual (English, "---", Chinese): show the half that matches the UI language. */
    fun notesFor(notes: String, chinese: Boolean): String {
        val parts = notes.split(Regex("\\n-{3,}\\n"))
        return (if (chinese && parts.size > 1) parts.last() else parts.first()).trim()
    }

    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val body = get(API).toString(Charsets.UTF_8)
        parse(body) ?: error("无法读取版本信息")
    }

    /** Downloads the APK to cache/update/, verifying the SHA-256 when the release provides one. */
    suspend fun download(context: Context, r: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = r.apkUrl ?: error("这个版本没有附带安装包")
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "HaniyaX-${r.version}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        open(url).let { c ->
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        r.sha256Url?.let { s ->
            val expected = get(s).toString(Charsets.UTF_8).trim().substringBefore(' ').lowercase()
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (expected.length == 64 && expected != actual) { out.delete(); error("下载的文件校验失败（SHA-256 不一致），请重试。") }
        }
        out
    }

    /** False when Android needs the user to allow installs from HaniyaX first (the settings page is opened). */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "HaniyaX")
        if (c.responseCode !in 200..299) error("暂时无法连接 (HTTP ${c.responseCode})")
        return c
    }

    private fun get(url: String): ByteArray = open(url).inputStream.use { it.readBytes() }
}
