package dev.haniya.x.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import dev.haniya.x.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** One entry in a directory listing. [path] is the guest path (what terminals and agents see). */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val isLink: Boolean,
)

/** Pure path helpers (unit-tested). */
object GuestPaths {
    /** Phone storage as seen inside the workspace (proot binds it at the same path). */
    const val PHONE = "/sdcard"

    fun isPhoneStorage(guest: String): Boolean =
        guest == PHONE || guest.startsWith("$PHONE/") || guest == "/storage" || guest.startsWith("/storage/")

    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(p)
        }
        return "/" + parts.joinToString("/")
    }

    fun parent(path: String): String? = if (path == "/") null else normalize("$path/..")

    fun child(dir: String, name: String): String = normalize("$dir/$name")

    /** A file name a user may type: no separators, not "." / "..", no NUL. */
    fun validName(name: String): String? = when {
        name.isBlank() -> "名称不能为空"
        name == "." || name == ".." -> "这个名称不可用"
        name.contains('/') || name.contains('\u0000') -> "名称里不能有 /"
        name.length > 200 -> "名称太长"
        else -> null
    }

    /** "~/projects/web" for paths under /root. */
    fun display(path: String): String = when {
        path == Paths.GUEST_HOME -> "~"
        path.startsWith(Paths.GUEST_HOME + "/") -> "~" + path.removePrefix(Paths.GUEST_HOME)
        path == PHONE -> "手机存储"
        path.startsWith("$PHONE/") -> "手机存储" + path.removePrefix(PHONE)
        else -> path
    }

    fun isTextName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXT || name.startsWith(".") && '.' !in name.drop(1) || name in TEXT_NAMES
    }

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "log", "csv", "tsv",
        "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte", "py", "rb", "go",
        "rs", "java", "kt", "kts", "gradle", "c", "h", "cc", "cpp", "hpp", "cs", "swift", "php", "sh", "bash", "zsh",
        "fish", "sql", "lua", "dart", "r", "pl", "properties", "lock", "gitignore", "dockerfile", "makefile", "svg",
    )
    private val TEXT_NAMES = setOf("Makefile", "Dockerfile", "LICENSE", "README", "Procfile", "Gemfile", "Rakefile")
}

/** Workspace + phone-storage file operations on host paths. All functions do blocking IO; call on [Dispatchers.IO]. */
object FileStore {
    /** Host file backing a guest path: phone storage is bound 1:1, everything else lives in the rootfs. */
    fun host(guest: String): File {
        val g = GuestPaths.normalize(guest)
        return if (GuestPaths.isPhoneStorage(g)) File(g) else Paths.host(g)
    }

    fun list(guest: String, showHidden: Boolean): List<FileEntry> {
        val dir = host(guest)
        val files = dir.listFiles() ?: throw IOException(if (dir.exists()) "没有权限读取这个文件夹" else "文件夹不存在")
        return files.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val link = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
                FileEntry(f.name, GuestPaths.child(guest, f.name), f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified(), link)
            }
            .sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    fun mkdir(dir: String, name: String): String {
        GuestPaths.validName(name)?.let { throw IOException(it) }
        val path = GuestPaths.child(dir, name)
        val f = host(path)
        if (f.exists()) throw IOException("已存在同名的文件或文件夹")
        if (!f.mkdirs()) throw IOException("无法创建文件夹")
        return path
    }

    fun rename(path: String, newName: String): String {
        GuestPaths.validName(newName)?.let { throw IOException(it) }
        val parent = GuestPaths.parent(path) ?: throw IOException("不能重命名根目录")
        val to = GuestPaths.child(parent, newName)
        val dst = host(to)
        if (dst.exists()) throw IOException("已存在同名的文件或文件夹")
        if (!host(path).renameTo(dst)) throw IOException("重命名失败")
        return to
    }

    /** Deletes a file or a whole folder (symlinks are removed, never followed). */
    fun delete(path: String) {
        val g = GuestPaths.normalize(path)
        if (g == "/" || g == Paths.GUEST_HOME || g == GuestPaths.PHONE) throw IOException("不能删除这个位置")
        val f = host(g)
        if (!deleteTree(f)) throw IOException("部分文件无法删除")
    }

    private fun deleteTree(f: File): Boolean {
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        if (f.isDirectory && !isLink) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() || !f.exists()
    }

    /** Reads up to [max] bytes as UTF-8 text; null when the content looks binary. */
    fun readText(path: String, max: Int = 512 * 1024): Pair<String, Boolean>? {
        val f = host(path)
        val len = f.length()
        val bytes = f.inputStream().use { input ->
            val buf = ByteArray(minOf(len, max.toLong()).toInt())
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            buf.copyOf(off)
        }
        if (bytes.take(8000).any { it == 0.toByte() }) return null
        return String(bytes, Charsets.UTF_8) to (len > max)
    }

    fun writeText(path: String, text: String) {
        val f = host(path)
        val tmp = File(f.parentFile, ".${f.name}.hx-tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /** Copies content:// documents into [dir]; returns the names written (existing names get a numbered suffix). */
    fun import(context: Context, uris: List<Uri>, dir: String): List<String> {
        val out = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(context, uri) ?: "imported"
            val target = uniqueChild(dir, sanitize(name))
            context.contentResolver.openInputStream(uri)?.use { input ->
                host(target).outputStream().use { input.copyTo(it) }
            } ?: throw IOException("无法读取 $name")
            out += target.substringAfterLast('/')
        }
        return out
    }

    fun export(context: Context, path: String, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> host(path).inputStream().use { it.copyTo(out) } }
            ?: throw IOException("无法写入目标文件")
    }

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: if (GuestPaths.isTextName(name)) "text/plain" else "application/octet-stream"

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sanitize(name: String) = name.replace('/', '_').replace('\u0000', '_').ifBlank { "imported" }

    private fun uniqueChild(dir: String, name: String): String {
        var candidate = GuestPaths.child(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        while (host(candidate).exists()) candidate = GuestPaths.child(dir, "$base ($i)$ext").also { i++ }
        return candidate
    }
}

/**
 * Access to phone storage (/sdcard). Granting it makes /sdcard visible inside the workspace too, so a project can
 * live in a folder the user already uses.
 */
object PhoneStorage {
    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    fun refresh(context: Context): Boolean {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val listable = ok && runCatching { File(GuestPaths.PHONE).list() != null }.getOrDefault(false)
        if (listable != _granted.value) {
            _granted.value = listable
            // New processes must bind /sdcard: drop the cached proot bind probe.
            (dev.haniya.x.runtime.Runtime.workspace as? dev.haniya.x.runtime.WorkspaceImpl)?.refreshStorageBinds()
        }
        return listable
    }

    /** Intent that opens the system "all files access" page for this app (Android 11+). */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    suspend fun refreshAsync(context: Context) = withContext(Dispatchers.IO) { refresh(context) }
}
