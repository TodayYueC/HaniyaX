package dev.haniya.x.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InstallLogicTest {
    // ---- LineSplitter ----

    private fun split(vararg chunks: String): List<String> {
        val out = ArrayList<String>()
        val s = LineSplitter { out += it }
        chunks.forEach { s.feed(it) }
        s.finish()
        return out
    }

    @Test
    fun `splits on LF and CRLF across chunk boundaries`() {
        assertEquals(listOf("one", "two", "three"), split("one\ntw", "o\r", "\nthr", "ee"))
    }

    @Test
    fun `bare CR redraws keep only the final state`() {
        assertEquals(listOf("100%", "done"), split("10%\r50%\r100%\ndone\n"))
    }

    @Test
    fun `blank lines are dropped`() {
        assertEquals(listOf("a", "b"), split("a\n\n   \nb\n"))
    }

    @Test
    fun `a trailing CR does not erase the last line`() {
        assertEquals(listOf("last"), split("last\r"))
    }

    // ---- AptProgress ----

    @Test
    fun `apt status lines map to overall progress`() {
        val apt = AptProgress()
        assertEquals(0f, apt.feed("::hx-phase:: update")!!, 0f)
        assertEquals(AptProgress.UPDATE_SHARE * 0.5f, apt.feed("dlstatus:1:50.0000:Retrieving file 1 of 3")!!, 1e-5f)
        assertEquals(AptProgress.UPDATE_SHARE, apt.feed("::hx-phase:: install")!!, 0f)
        assertEquals(AptProgress.Phase.Install, apt.phase)
        assertEquals(
            AptProgress.UPDATE_SHARE + AptProgress.DOWNLOAD_SHARE,
            apt.feed("dlstatus:12:100.0000:Retrieving file 40 of 40")!!, 1e-5f,
        )
        assertEquals(1f, apt.feed("pmstatus:psmisc:100:Installed psmisc")!!, 1e-5f)
    }

    @Test
    fun `pmstatus with a package architecture still parses`() {
        val apt = AptProgress()
        apt.feed("::hx-phase:: install")
        val p = apt.feed("pmstatus:libc6:arm64:50.0000:Unpacking libc6 (arm64)")!!
        val expected = AptProgress.UPDATE_SHARE + AptProgress.DOWNLOAD_SHARE + (1f - AptProgress.UPDATE_SHARE - AptProgress.DOWNLOAD_SHARE) * 0.5f
        assertEquals(expected, p, 1e-5f)
        assertEquals("Unpacking libc6 (arm64)", apt.describe("pmstatus:libc6:arm64:50.0000:Unpacking libc6 (arm64)"))
    }

    @Test
    fun `describe keeps colons inside the text`() {
        assertEquals("Retrieving http://deb.debian.org/x", AptProgress().describe("dlstatus:3:10.0:Retrieving http://deb.debian.org/x"))
    }

    @Test
    fun `non status lines`() {
        val apt = AptProgress()
        assertNull(apt.feed("Get:1 http://deb.debian.org/debian trixie InRelease"))
        assertNull(apt.feed("pmstatus:broken"))
        assertNull(apt.describe("Reading package lists..."))
        assertTrue(apt.isProtocol("dlstatus:1:2:x"))
        assertTrue(apt.isProtocol("pmconffile:/etc/x"))
        assertTrue(apt.isProtocol("::hx-phase:: update"))
        assertFalse(apt.isProtocol("Setting up curl (8.14.1-2) ..."))
    }

    // ---- FailureClassifier ----

    @Test
    fun `classifies the likely cause`() {
        assertEquals("网络不可用", FailureClassifier.cause(listOf("Err:1 http://deb.debian.org/debian trixie InRelease", "  Temporary failure resolving 'deb.debian.org'"), null, 100))
        assertEquals("存储空间不足", FailureClassifier.cause(emptyList(), IOException("write failed: ENOSPC (No space left on device)"), null))
        assertEquals("被系统中止", FailureClassifier.cause(listOf("Killed"), null, 137))
        assertEquals("另一个软件安装进程正在运行", FailureClassifier.cause(listOf("E: Could not get lock /var/lib/dpkg/lock-frontend"), null, 100))
        assertNull(FailureClassifier.cause(listOf("something odd"), null, 2))
    }

    @Test
    fun `message names the step and gives guidance`() {
        val m = FailureClassifier.message(InstallStep.Packages, listOf("Could not resolve 'deb.debian.org'"), StepExit(100), 100)
        assertTrue(m, m.startsWith("「安装基础软件」未完成。"))
        assertTrue(m, "网络不可用" in m)
        val generic = FailureClassifier.message(null, emptyList(), StepExit(2), 2)
        assertTrue(generic, "退出码 2" in generic)
    }

    @Test
    fun `install failures keep their own sentence`() {
        val m = FailureClassifier.message(InstallStep.Rootfs, emptyList(), InstallFailure("存储空间不足：需要约 320 MB。"), null)
        assertEquals("「解压 Debian 系统」未完成。存储空间不足：需要约 320 MB。", m)
    }

    // ---- SdkInstaller ----

    @Test
    fun `executable bits for bin and shell scripts`() {
        assertTrue(SdkInstaller.isExecutable("bin/hx"))
        assertTrue(SdkInstaller.isExecutable("adapters/claude.sh"))
        assertTrue(SdkInstaller.isExecutable("dsh/bin/dsh-mobile"))
        assertFalse(SdkInstaller.isExecutable("docs/plugins.md"))
        assertFalse(SdkInstaller.isExecutable("adapters/claude.json"))
    }
}
