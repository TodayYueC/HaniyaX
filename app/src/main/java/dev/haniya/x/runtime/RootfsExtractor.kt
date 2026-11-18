package dev.haniya.x.runtime

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Counts bytes pulled from the underlying (compressed) stream, for progress. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) count += s
        return s
    }

    override fun markSupported(): Boolean = false
}

/** Pure helpers for tar entry handling (unit-tested). */
object TarRules {
    private const val S_SUID_SGID = 0xC00 // 06000
    private const val PERM_STICKY = 0x3FF // 01777
    private const val PERM_ALL = 0x1FF    // 0777
    private const val OWNER_RWX = 0x1C0   // 0700
    private const val OWNER_RW = 0x180    // 0600

    /**
     * Normalizes a tar entry name to a safe relative path ("./usr/bin/" -> "usr/bin"), or null when the entry
     * is the archive root itself or would escape the destination (absolute after stripping, or contains "..").
     */
    fun normalize(name: String): String? {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/').trimEnd('/')
        if (n.isEmpty() || n == ".") return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Directory mode: keep sticky + permission bits, always owner-rwx so extraction can continue. */
    fun dirMode(mode: Int): Int = ((mode and PERM_STICKY) and S_SUID_SGID.inv()) or OWNER_RWX

    /** File mode: permission bits only (setuid/setgid are meaningless here), always owner-rw. */
    fun fileMode(mode: Int): Int = (mode and PERM_ALL) or OWNER_RW
}

/**
 * Extracts `.tar.xz` archives (the bundled proot runtime and the Debian rootfs) onto app storage, which
 * cannot hold device nodes or hard links: symlinks via Os.symlink, hard links become copies, device nodes
 * and fifos are skipped, permission bits preserved via Os.chmod.
 */
object RootfsExtractor {
    data class Stats(val entries: Int, val skipped: Int, val hardLinksCopied: Int)

    /**
     * Opens [assetPath] (stored uncompressed in the APK so openFd works) and extracts it into [dest].
     * [onProgress] receives compressed bytes consumed / asset length (null when the length is unknown).
     */
    fun extractAsset(
        context: Context,
        assetPath: String,
        dest: File,
        onProgress: (Float?) -> Unit = {},
        onLog: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Stats {
        val am = context.assets
        val (stream, length) = try {
            val afd = am.openFd(assetPath)
            afd.createInputStream() as InputStream to afd.length
        } catch (_: IOException) {
            // Compressed inside the APK (openFd refuses): fall back to a plain stream without a known length.
            am.open(assetPath) to -1L
        }
        return stream.use { extract(it, length, dest, onProgress, onLog, cancelled) }
    }

    fun extract(
        raw: InputStream,
        totalBytes: Long,
        dest: File,
        onProgress: (Float?) -> Unit = {},
        onLog: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Stats {
        dest.mkdirs()
        val destCanon = dest.canonicalFile
        val destPrefix = destCanon.path + File.separator
        val safeParents = HashSet<String>()
        val counter = CountingInputStream(raw)
        val deferredLinks = ArrayList<Triple<String, String, Int>>() // target, path, mode
        var entries = 0
        var skipped = 0
        var copied = 0
        var lastReported = -1f
        val buf = ByteArray(128 * 1024)

        fun report(force: Boolean = false) {
            if (totalBytes <= 0) { if (force) onProgress(null); return }
            val p = (counter.count.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            if (force || p - lastReported >= 0.005f) { lastReported = p; onProgress(p) }
        }

        fun ensureParent(file: File) {
            val parent = file.parentFile ?: return
            val key = parent.path
            if (key in safeParents) return
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) throw IOException("无法创建目录 ${parent.path}")
            val canon = parent.canonicalPath
            if (canon != destCanon.path && !canon.startsWith(destPrefix)) {
                throw IOException("压缩包条目指向了工作区之外：${file.path}")
            }
            safeParents += key
        }

        fun removeExisting(file: File) {
            val p = file.toPath()
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) deleteTree(file) else Files.delete(p)
            }
        }

        TarArchiveInputStream(XZInputStream(BufferedInputStream(counter, 256 * 1024))).use { tar ->
            while (true) {
                if (cancelled()) throw CancellationException("已取消")
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val rel = TarRules.normalize(entry.name)
                if (rel == null) continue
                val out = File(destCanon, rel)
                entries++
                when {
                    entry.isDirectory -> {
                        if (!Files.isDirectory(out.toPath(), LinkOption.NOFOLLOW_LINKS)) { removeExisting(out); ensureParent(out); out.mkdirs() }
                        chmodQuiet(out, TarRules.dirMode(entry.mode))
                    }
                    entry.isSymbolicLink -> {
                        ensureParent(out)
                        removeExisting(out)
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isLink -> {
                        ensureParent(out)
                        val target = TarRules.normalize(entry.linkName)
                        if (target == null) { skipped++; onLog("跳过硬链接 $rel → ${entry.linkName}") }
                        else if (!copyHardLink(destCanon, target, out, entry.mode)) deferredLinks += Triple(target, rel, entry.mode)
                        else copied++
                    }
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skipped++
                    entry.isFile -> {
                        ensureParent(out)
                        removeExisting(out)
                        FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                report()
                            }
                        }
                        chmodQuiet(out, TarRules.fileMode(entry.mode))
                    }
                    else -> skipped++
                }
                report()
            }
        }
        for ((target, rel, mode) in deferredLinks) {
            val out = File(destCanon, rel)
            if (copyHardLink(destCanon, target, out, mode)) copied++
            else { skipped++; onLog("硬链接目标缺失，已跳过：$rel → $target") }
        }
        report(force = true)
        return Stats(entries, skipped, copied)
    }

    /** Hard links are not allowed on app storage: copy the target's bytes instead. */
    private fun copyHardLink(root: File, target: String, out: File, mode: Int): Boolean {
        val src = File(root, target).toPath()
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) return false
        val outPath = out.toPath()
        if (Files.exists(outPath, LinkOption.NOFOLLOW_LINKS)) Files.delete(outPath)
        if (Files.isSymbolicLink(src)) {
            // A hard link to a symlink: recreate the symlink. Following it on the host could leave the rootfs.
            Os.symlink(Files.readSymbolicLink(src).toString(), out.path)
            return true
        }
        if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) return false
        Files.copy(src, outPath, StandardCopyOption.REPLACE_EXISTING)
        chmodQuiet(out, TarRules.fileMode(mode))
        return true
    }

    private fun chmodQuiet(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (_: Throwable) { }
    }

    /**
     * Deletes a directory tree without following symlinks (a guest symlink such as /sdcard or an absolute
     * path must never make us delete host files outside the tree).
     */
    fun deleteTree(root: File) {
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) { Files.delete(start); return }
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Directories without owner write/exec (e.g. 0555 inside the guest) would block deletion.
                try { Os.chmod(dir.toString(), 0x1C0) } catch (_: Throwable) { }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                try { Files.deleteIfExists(file) } catch (_: IOException) { }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try { Files.deleteIfExists(dir) } catch (_: IOException) { }
                return FileVisitResult.CONTINUE
            }
        })
        if (Files.exists(start, LinkOption.NOFOLLOW_LINKS)) throw IOException("无法完全删除 ${root.path}")
    }
}
