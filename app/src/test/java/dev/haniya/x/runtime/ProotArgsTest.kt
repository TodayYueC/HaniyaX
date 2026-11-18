package dev.haniya.x.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotArgsTest {
    private val layout = ProotLayout(
        runtimeDir = "/data/data/dev.haniya.x/files/runtime",
        rootfsDir = "/data/data/dev.haniya.x/files/rootfs",
        filesDir = "/data/data/dev.haniya.x/files",
        tmpDir = "/data/data/dev.haniya.x/files/tmp",
    )

    private fun argsFor(
        argv: List<String> = listOf("/bin/bash", "-l"),
        cwd: String = "/root",
        extra: Map<String, String> = emptyMap(),
        optional: List<Bind> = emptyList(),
    ) = Proot.args(layout, cwd, Proot.guestEnv(extra), argv, optional)

    @Test
    fun `argv starts with proot and the fixed flags`() {
        val a = argsFor()
        assertEquals(
            listOf("proot", "--kill-on-exit", "--link2symlink", "-0", "--sysvipc", "-L", "-r", layout.rootfsDir),
            a.subList(0, 8),
        )
    }

    @Test
    fun `standard binds are present in order`() {
        val a = argsFor()
        val binds = a.indices.filter { a[it] == "-b" }.map { a[it + 1] }
        assertEquals(
            listOf(
                "/dev", "/proc", "/sys",
                "/proc/self/fd:/dev/fd",
                "/proc/self/fd/0:/dev/stdin",
                "/proc/self/fd/1:/dev/stdout",
                "/proc/self/fd/2:/dev/stderr",
                "${layout.rootfsDir}/tmp:/dev/shm",
                "/dev/urandom:/dev/random",
            ),
            binds,
        )
    }

    @Test
    fun `optional binds follow the standard ones`() {
        val a = argsFor(optional = listOf(Bind("/storage", "/storage"), Bind("${layout.fakeProcDir}/stat", "/proc/stat")))
        val binds = a.indices.filter { a[it] == "-b" }.map { a[it + 1] }
        assertEquals("/storage", binds[binds.size - 2])
        assertEquals("${layout.runtimeDir}/proc/stat:/proc/stat", binds.last())
    }

    @Test
    fun `cwd, env -i, guest env and guest argv come last in order`() {
        val argv = listOf("/bin/bash", "-lc", "echo hi")
        val a = argsFor(argv = argv, cwd = "/root/projects/demo", extra = mapOf("FOO" to "bar"))
        val w = a.indexOf("-w")
        assertEquals("/root/projects/demo", a[w + 1])
        assertEquals("/usr/bin/env", a[w + 2])
        assertEquals("-i", a[w + 3])
        assertEquals(argv, a.takeLast(3))
        val env = a.subList(w + 4, a.size - argv.size)
        assertEquals("HOME=/root", env.first())
        assertEquals("FOO=bar", env.last())
        assertTrue(env.all { '=' in it })
    }

    @Test
    fun `guest env is clean and complete`() {
        val env = Proot.guestEnv(emptyMap())
        assertEquals("/root", env["HOME"])
        assertEquals("root", env["USER"])
        assertEquals("root", env["LOGNAME"])
        assertEquals("/bin/bash", env["SHELL"])
        assertEquals(
            "/opt/haniyax/bin:/root/.local/bin:/root/.opencode/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            env["PATH"],
        )
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("truecolor", env["COLORTERM"])
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals("C.UTF-8", env["LC_ALL"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("1", env["HX_APP"])
        assertEquals("1", env["HX_API"])
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("PROOT_LOADER"))
    }

    @Test
    fun `extra env overrides base and invalid keys are dropped`() {
        val env = Proot.guestEnv(mapOf("TERM" to "dumb", "COLORFGBG" to "0;15", "BAD KEY" to "x", "1X" to "y", "" to "z"))
        assertEquals("dumb", env["TERM"])
        assertEquals("0;15", env["COLORFGBG"])
        assertFalse(env.containsKey("BAD KEY"))
        assertFalse(env.containsKey("1X"))
        assertFalse(env.containsKey(""))
        // Overriding keeps the original position (deterministic argv).
        assertEquals(Proot.BASE_GUEST_ENV.keys.toList(), env.keys.take(Proot.BASE_GUEST_ENV.size))
    }

    @Test
    fun `shell argv`() {
        assertEquals(listOf("/bin/bash", "-l"), Proot.shellArgv(null))
        assertEquals(listOf("/bin/bash", "-lc", "claude --continue"), Proot.shellArgv("claude --continue"))
    }

    @Test
    fun `host env for the proot process`() {
        val env = Proot.hostEnv(layout, "/system", "/data")
        assertEquals(
            listOf(
                "LD_LIBRARY_PATH=${layout.runtimeDir}/lib",
                "PROOT_LOADER=${layout.runtimeDir}/loader",
                "PROOT_TMP_DIR=${layout.tmpDir}",
                "HOME=${layout.filesDir}",
                "PATH=/system/bin",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
            ),
            env,
        )
        val bare = Proot.hostEnv(layout, null, "")
        assertTrue(bare.none { it.startsWith("ANDROID_") })
    }

    @Test
    fun `launch spec ties it together`() {
        val spec = Proot.launchSpec(layout, "/root", listOf("/bin/true"), emptyMap(), emptyList(), null, null)
        assertEquals("${layout.runtimeDir}/proot", spec.executable)
        assertEquals("proot", spec.args[0])
        assertEquals("/bin/true", spec.args.last())
        assertEquals(layout.filesDir, spec.hostCwd)
        assertTrue(spec.env.contains("PROOT_LOADER=${layout.loaderPath}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty guest argv is rejected`() {
        argsFor(argv = emptyList())
    }

    @Test
    fun `fake proc binds only for unreadable files`() {
        val unreadable = setOf("/proc/stat", "/proc/sys/kernel/cap_last_cap")
        val binds = Proot.fakeProcBinds(layout) { it !in unreadable }
        assertEquals(
            listOf(
                Bind("${layout.fakeProcDir}/stat", "/proc/stat"),
                Bind("${layout.fakeProcDir}/cap_last_cap", "/proc/sys/kernel/cap_last_cap"),
            ),
            binds,
        )
        assertTrue(Proot.fakeProcBinds(layout) { true }.isEmpty())
        assertEquals(Proot.FAKE_PROC_FILES.size, Proot.fakeProcBinds(layout) { false }.size)
        // Every fake file has content to write.
        assertTrue(Proot.FAKE_PROC_FILES.values.all { Proot.FAKE_PROC_CONTENT[it]?.isNotBlank() == true })
    }

    @Test
    fun `storage binds only when readable`() {
        assertEquals(listOf(Bind("/storage", "/storage")), Proot.storageBinds { it == "/storage" })
        assertTrue(Proot.storageBinds { false }.isEmpty())
        assertEquals(2, Proot.storageBinds { true }.size)
    }

    @Test
    fun `bind arg collapses identical paths`() {
        assertEquals("/dev", Bind("/dev", "/dev").arg())
        assertEquals("/a:/b", Bind("/a", "/b").arg())
    }
}
