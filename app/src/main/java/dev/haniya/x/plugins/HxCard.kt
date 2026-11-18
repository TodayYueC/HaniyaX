package dev.haniya.x.plugins

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The .hxcard format: a zip of the plugin directory (plugin.json at the root, or inside a single top-level
 * folder). local.json — the user's own param values — is never written and never read on import.
 * Pure JVM code (unit tested).
 */
object HxCard {
    const val MAX_ENTRIES = 64
    const val MAX_FILE_BYTES = 1L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
    /** Cap on the raw archive bytes read (skipped entries such as local.json still have to be read through). */
    const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024
    const val LOCAL_FILE = "local.json"
    const val MANIFEST_FILE = "plugin.json"

    class ImportException(message: String) : IOException(message)

    /**
     * Validates an entry name; returns the normalized relative path, or null for directory entries.
     * Throws [ImportException] for absolute paths, `..`, backslashes, drive letters or control characters.
     */
    fun safeEntryName(raw: String): String? {
        if (raw.isEmpty()) throw ImportException("压缩包中有空文件名")
        if (raw.contains('\\')) throw ImportException("压缩包中的路径不安全：$raw")
        if (raw.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(raw)) throw ImportException("压缩包中包含绝对路径：$raw")
        if (raw.any { it < ' ' || it == '\u007f' }) throw ImportException("压缩包中的文件名包含控制字符")
        val segs = raw.split('/')
        val clean = ArrayList<String>()
        for ((i, s) in segs.withIndex()) {
            if (s == "..") throw ImportException("压缩包中的路径不安全（包含 ..）：$raw")
            if (s == "." ) continue
            if (s.isEmpty()) { if (i == segs.lastIndex) continue else throw ImportException("压缩包中的路径不安全：$raw") }
            clean.add(s)
        }
        if (raw.endsWith("/")) return null
        if (clean.isEmpty()) throw ImportException("压缩包中的路径不安全：$raw")
        return clean.joinToString("/")
    }

    /**
     * Extracts [input] into the empty directory [dest] with zip-slip protection and size limits.
     * Skips local.json and macOS metadata. Returns the directory that contains plugin.json.
     */
    fun extract(input: InputStream, dest: File): File {
        dest.mkdirs()
        val root = dest.canonicalFile
        var entries = 0
        var total = 0L
        val written = ArrayList<String>()
        ZipInputStream(CappedInputStream(input, MAX_ARCHIVE_BYTES)).use { zin ->
            while (true) {
                val e: ZipEntry = zin.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw ImportException("压缩包中的文件过多（最多 $MAX_ENTRIES 个）")
                val name = safeEntryName(e.name) ?: continue
                val segs = name.split('/')
                if (segs.any { it == "__MACOSX" || it == ".DS_Store" }) continue
                if (segs.last() == LOCAL_FILE) continue
                val out = File(root, name)
                val canon = out.canonicalFile
                if (!canon.path.startsWith(root.path + File.separator)) throw ImportException("压缩包中的路径不安全：${e.name}")
                canon.parentFile?.mkdirs()
                var size = 0L
                canon.outputStream().use { os ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        size += n; total += n
                        if (size > MAX_FILE_BYTES) throw ImportException("文件 $name 超过 1 MB")
                        if (total > MAX_TOTAL_BYTES) throw ImportException("压缩包解压后超过 4 MB")
                        os.write(buf, 0, n)
                    }
                }
                written.add(name)
            }
        }
        if (MANIFEST_FILE in written) return root
        val nested = written.filter { it.endsWith("/$MANIFEST_FILE") && it.count { c -> c == '/' } == 1 }
        if (nested.size == 1) return File(root, nested[0].substringBefore('/'))
        throw ImportException("压缩包中没有找到 plugin.json")
    }

    /** Writes [pluginDir] as a .hxcard zip to [out]: regular files only, no local.json, no dotfiles, no symlinks. */
    fun export(pluginDir: File, out: OutputStream): List<String> {
        val root = pluginDir.toPath()
        val names = ArrayList<String>()
        var total = 0L
        ZipOutputStream(out).use { zout ->
            fun walk(dir: java.nio.file.Path, prefix: String) {
                val children = Files.newDirectoryStream(dir).use { ds -> ds.sortedBy { it.fileName.toString() } }
                for (c in children) {
                    val fn = c.fileName.toString()
                    if (fn.startsWith(".")) continue
                    if (Files.isSymbolicLink(c)) continue
                    val rel = if (prefix.isEmpty()) fn else "$prefix/$fn"
                    if (rel == LOCAL_FILE) continue
                    if (Files.isDirectory(c, LinkOption.NOFOLLOW_LINKS)) { walk(c, rel); continue }
                    if (!Files.isRegularFile(c, LinkOption.NOFOLLOW_LINKS)) continue
                    val size = Files.size(c)
                    if (size > MAX_FILE_BYTES) throw IOException("文件 $rel 超过 1 MB，无法导出")
                    total += size
                    if (total > MAX_TOTAL_BYTES) throw IOException("卡片目录超过 4 MB，无法导出")
                    if (names.size >= MAX_ENTRIES) throw IOException("卡片目录中的文件过多（最多 $MAX_ENTRIES 个）")
                    zout.putNextEntry(ZipEntry(rel).apply { time = Files.getLastModifiedTime(c).toMillis() })
                    Files.newInputStream(c).use { it.copyTo(zout) }
                    zout.closeEntry()
                    names.add(rel)
                }
            }
            walk(root, "")
        }
        if (MANIFEST_FILE !in names) throw IOException("卡片目录中没有 plugin.json")
        return names
    }
}

/** Fails with [HxCard.ImportException] once more than [max] bytes have been read. */
class CappedInputStream(private val inner: InputStream, private val max: Long) : InputStream() {
    private var count = 0L

    private fun add(n: Int): Int {
        if (n > 0) {
            count += n
            if (count > max) throw HxCard.ImportException("压缩包过大（上限 ${max / (1024 * 1024)} MB）")
        }
        return n
    }

    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = add(inner.read(b, off, len))

    override fun close() = inner.close()
}
