package dev.haniya.x.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HxCardTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun tmp(): File = Files.createTempDirectory("hxcard").toFile()

    @Test fun rejectsZipSlip() {
        for (bad in listOf("../evil.sh", "a/../../evil", "/etc/passwd", "C:/x", "a\\..\\b", "a//b")) {
            val dest = tmp()
            try {
                HxCard.extract(ByteArrayInputStream(zip("plugin.json" to "{}".toByteArray(), bad to "x".toByteArray())), File(dest, "out"))
                fail("expected rejection for $bad")
            } catch (_: HxCard.ImportException) {}
            assertFalse(File(dest, "evil.sh").exists())
            assertFalse(File(dest.parentFile, "evil").exists())
        }
    }

    @Test fun safeEntryNames() {
        assertEquals("a/b.json", HxCard.safeEntryName("./a/b.json"))
        assertNull(HxCard.safeEntryName("dir/"))
    }

    @Test fun extractsAndSkipsLocalJson() {
        val dest = tmp()
        val root = HxCard.extract(
            ByteArrayInputStream(zip("plugin.json" to "{\"id\":\"x\"}".toByteArray(), "local.json" to "{\"params\":{\"secret\":1}}".toByteArray(), "README.md" to "hi".toByteArray())),
            dest,
        )
        assertEquals(dest.canonicalFile, root)
        assertTrue(File(root, "plugin.json").exists())
        assertTrue(File(root, "README.md").exists())
        assertFalse(File(root, "local.json").exists())
    }

    @Test fun findsManifestInSingleTopFolder() {
        val dest = tmp()
        val root = HxCard.extract(ByteArrayInputStream(zip("my-card/plugin.json" to "{}".toByteArray())), dest)
        assertEquals("my-card", root.name)
    }

    @Test fun missingManifestFails() {
        try { HxCard.extract(ByteArrayInputStream(zip("x.txt" to "1".toByteArray())), tmp()); fail() } catch (e: HxCard.ImportException) {
            assertTrue(e.message!!.contains("plugin.json"))
        }
    }

    @Test fun sizeLimits() {
        val big = ByteArray((HxCard.MAX_FILE_BYTES + 1).toInt())
        try { HxCard.extract(ByteArrayInputStream(zip("plugin.json" to "{}".toByteArray(), "big.bin" to big)), tmp()); fail() } catch (_: HxCard.ImportException) {}
        val many = (0..HxCard.MAX_ENTRIES).map { "f$it" to "x".toByteArray() }.toTypedArray()
        try { HxCard.extract(ByteArrayInputStream(zip(*many)), tmp()); fail() } catch (_: HxCard.ImportException) {}
    }

    @Test fun exportExcludesLocalJsonDotfilesAndSymlinks() {
        val dir = tmp()
        File(dir, "plugin.json").writeText("{}")
        File(dir, "local.json").writeText("{\"params\":{\"token\":\"secret\"}}")
        File(dir, ".hidden").writeText("x")
        File(dir, "assets").mkdirs(); File(dir, "assets/a.txt").writeText("a")
        val outside = File(tmp(), "outside.txt").apply { writeText("outside") }
        runCatching { Files.createSymbolicLink(File(dir, "link.txt").toPath(), outside.toPath()) }
        val bo = ByteArrayOutputStream()
        val names = HxCard.export(dir, bo)
        assertEquals(listOf("assets/a.txt", "plugin.json"), names)
        val inZip = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bo.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; inZip.add(e.name) } }
        assertEquals(names, inZip)
    }
}
