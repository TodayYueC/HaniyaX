package dev.haniya.x.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestFilesTest {
    @Test fun resolvesSymlinksInsideRootfs() {
        val root = Files.createTempDirectory("rootfs").toFile()
        File(root, "var/log").mkdirs()
        File(root, "var/log/app.log").writeText("guest log\n")
        File(root, "root").mkdirs()
        // Absolute link inside the guest must be re-rooted under the rootfs, never the host's /var/log.
        Files.createSymbolicLink(File(root, "root/app.log").toPath(), File("/var/log/app.log").toPath())
        Files.createSymbolicLink(File(root, "root/up").toPath(), File("../../../../..").toPath())
        val f = GuestFiles.resolve(root, "/root/app.log")!!
        assertEquals(File(root, "var/log/app.log").canonicalPath, f.canonicalPath)
        assertEquals("guest log\n", f.readText())
        // ".." can never climb above the guest root.
        assertEquals(root.canonicalPath, GuestFiles.resolve(root, "/root/../../..")!!.canonicalPath)
        assertTrue(GuestFiles.resolve(root, "/root/up/etc")!!.canonicalPath.startsWith(root.canonicalPath))
        assertNull(GuestFiles.resolve(root, "relative/path"))
    }

    @Test fun symlinkLoopsAreRejected() {
        val root = Files.createTempDirectory("rootfs").toFile()
        Files.createSymbolicLink(File(root, "a").toPath(), File("/b").toPath())
        Files.createSymbolicLink(File(root, "b").toPath(), File("/a").toPath())
        assertNull(GuestFiles.resolve(root, "/a/x"))
    }

    @Test fun tailReadsOnlyTheEnd() {
        val f = File.createTempFile("tail", ".log")
        f.writeText((1..1000).joinToString("\n") { "line $it" } + "\n")
        assertEquals(listOf("line 999", "line 1000"), GuestFiles.tailLines(f, 2, 4096))
        val small = GuestFiles.tailLines(f, 100, 30)
        assertTrue(small.isNotEmpty() && small.all { it.startsWith("line ") })
        assertEquals("line 1000", small.last())
    }

    @Test fun deleteTreeDoesNotFollowLinks() {
        val keep = Files.createTempDirectory("keep").toFile()
        File(keep, "precious.txt").writeText("x")
        val dir = Files.createTempDirectory("plugin").toFile()
        File(dir, "sub").mkdirs(); File(dir, "sub/a").writeText("a")
        Files.createSymbolicLink(File(dir, "link").toPath(), keep.toPath())
        GuestFiles.deleteTree(dir)
        assertFalse(dir.exists())
        assertTrue(File(keep, "precious.txt").exists())
    }
}
