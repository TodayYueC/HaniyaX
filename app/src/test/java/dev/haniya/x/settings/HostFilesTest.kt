package dev.haniya.x.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HostFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writeAtomicCreatesParentsReplacesAndLeavesNoTemp() {
        val f = File(tmp.root, "state/deep/settings.json")
        HostFiles.writeAtomic(f, "one")
        assertEquals("one", f.readText())
        HostFiles.writeAtomic(f, "二")
        assertEquals("二", f.readText())
        assertEquals(listOf("settings.json"), f.parentFile!!.list()!!.toList())
    }

    @Test fun diskUsageCountsRegularFilesAndSkipsSymlinks() {
        val root = tmp.newFolder("rootfs")
        File(root, "a").writeBytes(ByteArray(100))
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/b").writeBytes(ByteArray(23))
        val outside = tmp.newFolder("outside")
        File(outside, "big").writeBytes(ByteArray(10_000))
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "flink").toPath(), File(outside, "big").toPath())
        assertEquals(123L, HostFiles.diskUsage(root))
        assertEquals(0L, HostFiles.diskUsage(File(tmp.root, "missing")))
    }

    @Test fun deleteTreeRemovesReadOnlyDirsButNeverFollowsLinks() {
        val root = tmp.newFolder("rootfs")
        val ro = File(root, "usr/share/ro").apply { mkdirs() }
        File(ro, "file").writeText("x")
        ro.setWritable(false, false)
        val outside = tmp.newFolder("keep")
        val precious = File(outside, "precious").apply { writeText("keep me") }
        Files.createSymbolicLink(File(root, "etc-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(tmp.root, "nowhere").toPath())

        assertTrue(HostFiles.deleteTree(root))
        assertFalse(root.exists())
        assertTrue(precious.isFile)
        assertEquals("keep me", precious.readText())
    }

    @Test fun deleteTreeOnMissingOrSingleFile() {
        assertTrue(HostFiles.deleteTree(File(tmp.root, "missing")))
        val f = tmp.newFile("single")
        assertTrue(HostFiles.deleteTree(f))
        assertFalse(f.exists())
    }
}
