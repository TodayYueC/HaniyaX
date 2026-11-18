package dev.haniya.x.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs on the plain JVM: Os.chmod is unavailable there and is ignored by the extractor. Symlinks are not exercised. */
class RootfsExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tarXz(build: TarArchiveOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        XZOutputStream(bos, LZMA2Options(1)).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.build()
            }
        }
        return bos.toByteArray()
    }

    private fun TarArchiveOutputStream.dir(name: String) {
        putArchiveEntry(TarArchiveEntry(name.trimEnd('/') + "/"))
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.file(name: String, text: String, mode: Int = 0x1A4) {
        val bytes = text.toByteArray()
        val e = TarArchiveEntry(name)
        e.size = bytes.size.toLong()
        e.mode = mode
        putArchiveEntry(e)
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.special(name: String, type: Byte, link: String? = null) {
        val e = TarArchiveEntry(name, type)
        if (link != null) e.linkName = link
        putArchiveEntry(e)
        closeArchiveEntry()
    }

    @Test
    fun `extracts files, dirs and copies hard links`() {
        val data = tarXz {
            dir("./")
            dir("usr/bin")
            file("usr/bin/perl", "#!perl")
            special("usr/bin/perl5.40.1", TarConstants.LF_LINK, "usr/bin/perl")
            special("dev/null", TarConstants.LF_CHR)
            special("run/fifo", TarConstants.LF_FIFO)
            file("etc/deep/nested/conf", "x=1")
        }
        val dest = File(tmp.root, "rootfs.partial")
        val progress = ArrayList<Float?>()
        val stats = RootfsExtractor.extract(ByteArrayInputStream(data), data.size.toLong(), dest, onProgress = { progress += it })

        assertEquals("#!perl", File(dest, "usr/bin/perl").readText())
        assertEquals("#!perl", File(dest, "usr/bin/perl5.40.1").readText())
        assertEquals("x=1", File(dest, "etc/deep/nested/conf").readText())
        assertFalse(File(dest, "dev/null").exists())
        assertFalse(File(dest, "run/fifo").exists())
        assertEquals(1, stats.hardLinksCopied)
        assertEquals(2, stats.skipped)
        assertEquals(1f, progress.last()!!, 0f)
        assertTrue("progress is monotonic", progress.filterNotNull().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `hard link before its target is resolved after the pass`() {
        val data = tarXz {
            special("b", TarConstants.LF_LINK, "a")
            file("a", "late")
        }
        val dest = File(tmp.root, "out")
        val stats = RootfsExtractor.extract(ByteArrayInputStream(data), -1, dest)
        assertEquals("late", File(dest, "b").readText())
        assertEquals(1, stats.hardLinksCopied)
    }

    @Test
    fun `entries escaping the destination are ignored`() {
        val data = tarXz {
            file("../escape.txt", "no")
            file("ok.txt", "yes")
        }
        val dest = File(tmp.root, "inner/dest")
        RootfsExtractor.extract(ByteArrayInputStream(data), data.size.toLong(), dest)
        assertFalse(File(tmp.root, "inner/escape.txt").exists())
        assertEquals("yes", File(dest, "ok.txt").readText())
    }

    @Test
    fun `unknown length reports indeterminate progress`() {
        val data = tarXz { file("a", "1") }
        val progress = ArrayList<Float?>()
        RootfsExtractor.extract(ByteArrayInputStream(data), -1, File(tmp.root, "x"), onProgress = { progress += it })
        assertEquals(listOf<Float?>(null), progress)
    }

    @Test
    fun `deleteTree removes nested trees`() {
        val root = File(tmp.root, "tree")
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/f").writeText("x")
        File(root, "top").writeText("y")
        RootfsExtractor.deleteTree(root)
        assertFalse(root.exists())
        RootfsExtractor.deleteTree(root) // missing is fine
    }

    @Test
    fun `tar names are normalized safely`() {
        assertEquals("usr/bin", TarRules.normalize("./usr/bin/"))
        assertEquals("etc/hosts", TarRules.normalize("/etc//hosts"))
        assertEquals("a/b", TarRules.normalize("./a/./b"))
        assertNull(TarRules.normalize("./"))
        assertNull(TarRules.normalize("."))
        assertNull(TarRules.normalize("../x"))
        assertNull(TarRules.normalize("a/../../x"))
    }

    @Test
    fun `modes keep permissions, drop setuid and stay owner writable`() {
        assertEquals(0x3FF, TarRules.dirMode(0x3FF))          // 01777 /tmp keeps sticky
        assertEquals(0x1ED, TarRules.dirMode(0x16D))          // 0555 -> 0755
        assertEquals(0x1C0, TarRules.dirMode(0x1C0))          // 0700 /root
        assertEquals(0x1ED, TarRules.fileMode(0x9ED))         // 04755 -> 0755
        assertEquals(0x1A4, TarRules.fileMode(0x124))         // 0444 -> 0644
        assertEquals(0x180, TarRules.fileMode(0))             // 0000 -> 0600
    }
}
