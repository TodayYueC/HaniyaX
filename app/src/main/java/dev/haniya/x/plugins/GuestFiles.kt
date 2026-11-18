package dev.haniya.x.plugins

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Host-side access to guest files. Symlinks are resolved *inside* the rootfs (an absolute link target such
 * as /var/log/x is re-rooted under [rootfs]), so a guest symlink can never make the app read a host file
 * outside the workspace. Pure JVM code (unit tested).
 */
object GuestFiles {
    private const val MAX_HOPS = 40

    /** Resolves [guestPath] (absolute) to a host file under [rootfs], or null if it escapes or loops. */
    fun resolve(rootfs: File, guestPath: String): File? {
        if (!guestPath.startsWith("/")) return null
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val pending = ArrayDeque(guestPath.split('/').filter { it.isNotEmpty() })
        val parts = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val seg = pending.removeFirst()
            when (seg) {
                "." -> continue
                ".." -> { if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); continue }
            }
            parts.add(seg)
            val p: Path = parts.fold(root) { acc, s -> acc.resolve(s) }
            if (Files.isSymbolicLink(p)) {
                if (++hops > MAX_HOPS) return null
                val target = try { Files.readSymbolicLink(p).toString() } catch (_: Exception) { return null }
                parts.removeAt(parts.lastIndex)
                if (target.startsWith("/")) parts.clear()
                val tsegs = target.split('/').filter { it.isNotEmpty() }
                for (i in tsegs.indices.reversed()) pending.addFirst(tsegs[i])
            }
        }
        val out = parts.fold(root) { acc, s -> acc.resolve(s) }.normalize()
        if (!out.startsWith(root)) return null
        return out.toFile()
    }

    /** Reads at most [maxBytes] from the end of [f] (whole file if smaller), decoding UTF-8 leniently. */
    fun readTail(f: File, maxBytes: Int): String {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            val n = (len - start).toInt()
            val buf = ByteArray(n)
            raf.seek(start)
            raf.readFully(buf)
            var s = String(buf, Charsets.UTF_8)
            // Drop a partial first line when we started in the middle of the file.
            if (start > 0) s = s.substringAfter('\n', s)
            return s
        }
    }

    /** Last [n] lines of [f], reading at most [maxBytes] from its end. */
    fun tailLines(f: File, n: Int, maxBytes: Int): List<String> = SourceValue.splitLines(readTail(f, maxBytes)).takeLast(n)

    /** Deletes a directory tree without ever following symlinks. */
    fun deleteTree(dir: File) {
        val p = dir.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(p); return }
        Files.newDirectoryStream(p).use { ds -> for (c in ds) deleteTree(c.toFile()) }
        Files.deleteIfExists(p)
    }
}
