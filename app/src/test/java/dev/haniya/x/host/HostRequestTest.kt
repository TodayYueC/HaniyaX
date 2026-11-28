package dev.haniya.x.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRequestTest {
    private fun plan(vararg args: String, root: Boolean = false) =
        HostRequest.command(args.toList(), root, 0, 0, "/storage/emulated/0")

    @Test fun `routine grants only fixed ordinary reads`() {
        val p = plan("shell", "pm list packages")
        assertEquals(listOf("exec", "/system/bin/pm", "list", "packages"), p.nativeArgs)
        assertTrue(p.needsApproval(HostPolicy.Manual))
        assertFalse(p.needsApproval(HostPolicy.Routine))
        assertTrue(plan("root", "id").needsApproval(HostPolicy.Routine))
        assertTrue(plan("logcat").needsApproval(HostPolicy.Routine))
        assertFalse(plan("apps", root = true).needsApproval(HostPolicy.Full))
    }

    @Test fun `full authorization runs shells and mutations without approval`() {
        for (request in listOf(
            plan("shell", "getprop; reboot"), plan("shell", "getprop\ngetprop"),
            plan("root"), plan("shell"), plan("install", "4096", "fixture.apk"),
            plan("settings", "put", "system", "key", "a b"), plan("settings", "delete", "global", "key"),
            plan("push", "/sdcard/file", "42"),
        )) {
            assertTrue(request.needsApproval(HostPolicy.Manual))
            assertTrue(request.needsApproval(HostPolicy.Routine))
            assertFalse(request.needsApproval(HostPolicy.Full))
        }
        assertTrue(plan("install", "4096", "fixture.apk").root)
        assertTrue(plan("root").tty)
    }

    @Test fun `typed storage paths are scoped and sizes checked`() {
        assertEquals(listOf("pull", "/storage/emulated/0/test x"), plan("pull", "/sdcard/test x").nativeArgs)
        assertEquals(listOf("push", "/data/local/tmp/sample", "0"), plan("push", "/data/local/tmp/sample", "0").nativeArgs)
        for (path in listOf("/sdcard/../local", "/sdcard//test", "/data/data/dev.haniya.x/settings", "relative")) {
            assertTrue(runCatching { plan("pull", path) }.isFailure)
        }
        assertTrue(runCatching { plan("push", "/sdcard/a", "1073741825") }.isFailure)
    }

    @Test fun `approvals show control characters without rendering them`() {
        assertEquals("root \"echo a\\u000aecho b\\u202e\"", plan("root", "echo a\necho b\u202e").description)
    }
}
