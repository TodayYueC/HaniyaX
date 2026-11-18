package dev.haniya.x.settings

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Host-side file helpers shared by the settings, home and services modules. Pure JVM (no Android APIs),
 * so they are covered by plain unit tests. Never follows symbolic links: the guest rootfs is full of
 * absolute symlinks that would resolve to host paths outside the app sandbox.
 */
object HostFiles {
    /** Writes [text] to [file] atomically: temp file in the same directory, fsync, rename over the target. */
    fun writeAtomic(file: File, text: String) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("无效路径：${file.path}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建目录：${dir.path}")
        val tmp = File(dir, ".${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("无法写入：${file.path}")
            }
        }
    }

    /** Total size in bytes of the regular files under [root] (symlinks are not followed or counted). */
    fun diskUsage(root: File): Long {
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }

    /**
     * Deletes [root] and everything below it without following symlinks (a link is removed, its target is
     * left alone). Directories are made writable first because rootfs trees contain read-only dirs.
     * Returns true when [root] no longer exists.
     */
    fun deleteTree(root: File): Boolean {
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(start) || !Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { Files.deleteIfExists(start) }.isSuccess && !Files.exists(start, LinkOption.NOFOLLOW_LINKS)
        }
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val f = dir.toFile()
                f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Unreadable directory: try to open it up and remove what we can.
                val f = file.toFile()
                f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    f.listFiles()?.forEach { deleteTree(it) }
                }
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.deleteIfExists(dir) }
                return FileVisitResult.CONTINUE
            }
        })
        return !Files.exists(start, LinkOption.NOFOLLOW_LINKS)
    }
}
