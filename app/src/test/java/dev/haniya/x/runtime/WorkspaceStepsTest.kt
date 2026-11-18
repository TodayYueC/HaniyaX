package dev.haniya.x.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStepsTest {
    @Test
    fun `steps are in install order with Chinese labels`() {
        assertEquals(
            listOf("解压运行时", "解压 Debian 系统", "配置网络与基础环境", "安装基础软件", "安装 HaniyaX 工具"),
            InstallStep.entries.map { it.label },
        )
        assertEquals(InstallStep.Packages, InstallStep.byId("packages"))
        assertEquals(InstallStep.Rootfs, InstallStep.byLabel("解压 Debian 系统"))
        assertNull(InstallStep.byId("nope"))
    }

    @Test
    fun `record round-trips through JSON`() {
        val r = WorkspaceRecord(done = listOf("runtime", "rootfs"), installedAt = 1234L)
        val back = WorkspaceSteps.decode(WorkspaceSteps.encode(r))
        assertEquals(r, back)
        assertTrue(back.isDone(InstallStep.Runtime))
        assertFalse(back.isDone(InstallStep.Sdk))
    }

    @Test
    fun `missing, blank and corrupt files decode to not installed`() {
        assertEquals(WorkspaceRecord(), WorkspaceSteps.decode(null))
        assertEquals(WorkspaceRecord(), WorkspaceSteps.decode("  "))
        assertEquals(WorkspaceRecord(), WorkspaceSteps.decode("{not json"))
        assertEquals(WorkspaceRecord(), WorkspaceSteps.decode("[1,2,3]"))
    }

    @Test
    fun `unknown fields are ignored`() {
        val r = WorkspaceSteps.decode("""{"version":1,"done":["runtime"],"future":true}""")
        assertEquals(listOf("runtime"), r.done)
    }

    @Test
    fun `allDone needs every step`() {
        var r = WorkspaceRecord()
        for (s in InstallStep.entries) {
            assertFalse(WorkspaceSteps.allDone(r))
            r = r.markDone(s)
        }
        assertTrue(WorkspaceSteps.allDone(r))
    }

    @Test
    fun `markDone is idempotent and unmark removes`() {
        val r = WorkspaceRecord().markDone(InstallStep.Runtime).markDone(InstallStep.Runtime)
        assertEquals(listOf("runtime"), r.done)
        assertEquals(emptyList<String>(), r.unmark(listOf(InstallStep.Runtime)).done)
    }

    @Test
    fun `pending starts at the first unfinished step`() {
        val r = WorkspaceRecord(done = listOf("runtime", "rootfs"))
        assertEquals(
            listOf(InstallStep.Configure, InstallStep.Packages, InstallStep.Sdk),
            WorkspaceSteps.pending(r) { true },
        )
    }

    @Test
    fun `pending redoes everything after a step whose artifact is missing`() {
        val all = InstallStep.entries.fold(WorkspaceRecord()) { acc, s -> acc.markDone(s) }
        assertEquals(emptyList<InstallStep>(), WorkspaceSteps.pending(all) { true })
        // The rootfs was deleted: re-extract it and redo everything after it.
        assertEquals(
            listOf(InstallStep.Rootfs, InstallStep.Configure, InstallStep.Packages, InstallStep.Sdk),
            WorkspaceSteps.pending(all) { it != InstallStep.Rootfs },
        )
    }

    @Test
    fun `a later done marker does not skip an earlier unfinished step`() {
        val r = WorkspaceRecord(done = listOf("runtime", "sdk"))
        assertEquals(InstallStep.entries.drop(1), WorkspaceSteps.pending(r) { true })
    }

    @Test
    fun `fresh record runs every step`() {
        assertEquals(InstallStep.entries.toList(), WorkspaceSteps.pending(WorkspaceRecord()) { false })
    }
}
