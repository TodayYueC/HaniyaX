package dev.haniya.x.runtime

import java.io.File
import java.io.FileInputStream

/** Host-side directories proot needs. Plain strings so the builder stays a pure, JVM-testable function. */
data class ProotLayout(
    /** Directory holding proot, loader and lib/. */
    val runtimeDir: String,
    /** Guest root filesystem on the host. */
    val rootfsDir: String,
    /** App filesDir (host HOME and host cwd for the proot process). */
    val filesDir: String,
    /** PROOT_TMP_DIR. */
    val tmpDir: String,
) {
    val prootPath get() = "$runtimeDir/proot"
    val loaderPath get() = "$runtimeDir/loader"
    val libDir get() = "$runtimeDir/lib"
    val fakeProcDir get() = "$runtimeDir/proc"
}

/** One `-b host:guest` bind. */
data class Bind(val host: String, val guest: String) {
    fun arg(): String = if (host == guest) host else "$host:$guest"
}

/**
 * Proot launch construction. Everything that decides argv/env is pure (no Android APIs, no IO) so it can
 * be unit tested; the small IO helpers ([probeBinds], [writeFakeProc]) are kept separate.
 */
object Proot {
    const val GUEST_PATH =
        "/opt/haniyax/bin:/root/.local/bin:/root/.opencode/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** The clean base environment every guest process starts with (before extraEnv). Ordered. */
    val BASE_GUEST_ENV: Map<String, String> = linkedMapOf(
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to "/bin/bash",
        "PATH" to GUEST_PATH,
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "HX_APP" to "1",
        "HX_API" to "1",
    )

    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** True for names a shell can export. Invalid keys in extraEnv are dropped rather than corrupting argv. */
    fun isValidEnvKey(key: String): Boolean = ENV_KEY.matches(key)

    /** Base env with [extra] merged on top (extra wins; invalid keys dropped). Ordered, deterministic. */
    fun guestEnv(extra: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap(BASE_GUEST_ENV)
        for ((k, v) in extra) if (isValidEnvKey(k)) out[k] = v
        return out
    }

    /** Guest argv for an interactive terminal: login shell, or a login shell running [command]. */
    fun shellArgv(command: String?): List<String> =
        if (command == null) listOf("/bin/bash", "-l") else listOf("/bin/bash", "-lc", command)

    /** Fixed binds every launch gets (order matters: /dev before the /dev/... overrides). */
    fun standardBinds(rootfsDir: String): List<Bind> = listOf(
        Bind("/dev", "/dev"),
        Bind("/proc", "/proc"),
        Bind("/sys", "/sys"),
        Bind("/proc/self/fd", "/dev/fd"),
        Bind("/proc/self/fd/0", "/dev/stdin"),
        Bind("/proc/self/fd/1", "/dev/stdout"),
        Bind("/proc/self/fd/2", "/dev/stderr"),
        Bind("$rootfsDir/tmp", "/dev/shm"),
        Bind("/dev/urandom", "/dev/random"),
    )

    /** Fake /proc entries (guest path -> file name under [ProotLayout.fakeProcDir]). */
    val FAKE_PROC_FILES: Map<String, String> = linkedMapOf(
        "/proc/loadavg" to "loadavg",
        "/proc/stat" to "stat",
        "/proc/uptime" to "uptime",
        "/proc/version" to "version",
        "/proc/vmstat" to "vmstat",
        "/proc/sys/kernel/cap_last_cap" to "cap_last_cap",
    )

    /** Binds that replace unreadable /proc files with static copies. [readable] probes the real host path. */
    fun fakeProcBinds(layout: ProotLayout, readable: (String) -> Boolean): List<Bind> =
        FAKE_PROC_FILES.mapNotNull { (guest, name) ->
            if (readable(guest)) null else Bind("${layout.fakeProcDir}/$name", guest)
        }

    /** /storage and /sdcard are bound only when the app can actually read them. */
    fun storageBinds(readableDir: (String) -> Boolean): List<Bind> =
        listOf("/storage", "/sdcard").filter(readableDir).map { Bind(it, it) }

    /**
     * Full argv for the proot process (argv[0] = "proot").
     * [optionalBinds] = storage + fake proc binds decided by the caller.
     */
    fun args(
        layout: ProotLayout,
        guestCwd: String,
        guestEnv: Map<String, String>,
        argv: List<String>,
        optionalBinds: List<Bind> = emptyList(),
    ): List<String> {
        require(argv.isNotEmpty()) { "guest argv must not be empty" }
        val out = ArrayList<String>(48 + guestEnv.size + argv.size)
        out += "proot"
        out += listOf("--kill-on-exit", "--link2symlink", "-0", "--sysvipc", "-L", "-r", layout.rootfsDir)
        for (b in standardBinds(layout.rootfsDir) + optionalBinds) { out += "-b"; out += b.arg() }
        out += "-w"; out += guestCwd
        out += "/usr/bin/env"; out += "-i"
        for ((k, v) in guestEnv) out += "$k=$v"
        out += argv
        return out
    }

    /** Host environment for the proot process itself. */
    fun hostEnv(layout: ProotLayout, androidRoot: String?, androidData: String?): List<String> = buildList {
        add("LD_LIBRARY_PATH=${layout.libDir}")
        add("PROOT_LOADER=${layout.loaderPath}")
        add("PROOT_TMP_DIR=${layout.tmpDir}")
        add("HOME=${layout.filesDir}")
        add("PATH=/system/bin")
        if (!androidRoot.isNullOrEmpty()) add("ANDROID_ROOT=$androidRoot")
        if (!androidData.isNullOrEmpty()) add("ANDROID_DATA=$androidData")
    }

    /** Builds the complete [LaunchSpec]. Pure given its inputs. */
    fun launchSpec(
        layout: ProotLayout,
        guestCwd: String,
        argv: List<String>,
        extraEnv: Map<String, String>,
        optionalBinds: List<Bind>,
        androidRoot: String?,
        androidData: String?,
    ): LaunchSpec = LaunchSpec(
        executable = layout.prootPath,
        args = args(layout, guestCwd, guestEnv(extraEnv), argv, optionalBinds).toTypedArray(),
        env = hostEnv(layout, androidRoot, androidData).toTypedArray(),
        hostCwd = layout.filesDir,
    )

    // ---- small IO helpers (not pure) ----

    /** True if the file can really be opened and read (SELinux may deny reads that access() allows). */
    fun canReallyRead(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (_: Throwable) {
        false
    }

    fun canListDir(path: String): Boolean = try {
        val f = File(path)
        f.isDirectory && f.canRead() && f.list() != null
    } catch (_: Throwable) {
        false
    }

    /** Optional binds for this device, probed once per process (results don't change while we run). */
    fun probeBinds(layout: ProotLayout): List<Bind> =
        storageBinds(::canListDir) + fakeProcBinds(layout, ::canReallyRead)

    /** Writes the static /proc replacements (like proot-distro does). */
    fun writeFakeProc(layout: ProotLayout) {
        val dir = File(layout.fakeProcDir).apply { mkdirs() }
        for ((name, content) in FAKE_PROC_CONTENT) File(dir, name).writeText(content)
    }

    val FAKE_PROC_CONTENT: Map<String, String> = linkedMapOf(
        "loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "stat" to buildString {
            append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
            for (i in 0 until 8) append("cpu$i 245 0 360 11660 33 43 32 11 0 0\n")
            append("intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n")
            append("ctxt 38014093\n")
            append("btime 1694292441\n")
            append("processes 26442\n")
            append("procs_running 1\n")
            append("procs_blocked 0\n")
            append("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
        },
        "uptime" to "124.08 932.80\n",
        "version" to "Linux version 6.2.1-HaniyaX (haniyax@localhost) (gcc (GCC) 12.2.1, GNU ld (GNU Binutils) 2.40) #1 SMP PREEMPT_DYNAMIC Wed, 01 Mar 2023 00:00:00 +0000\n",
        "vmstat" to listOf(
            "nr_free_pages 136500", "nr_zone_inactive_anon 196744", "nr_zone_active_anon 9", "nr_zone_inactive_file 263384",
            "nr_zone_active_file 294497", "nr_zone_unevictable 0", "nr_zone_write_pending 0", "nr_mlock 0",
            "nr_bounce 0", "nr_zspages 0", "nr_free_cma 0", "numa_hit 2617", "numa_miss 0", "numa_foreign 0",
            "numa_interleave 1", "numa_local 2617", "numa_other 0", "nr_inactive_anon 196744", "nr_active_anon 9",
            "nr_inactive_file 263384", "nr_active_file 294497", "nr_unevictable 0", "nr_slab_reclaimable 26545",
            "nr_slab_unreclaimable 34069", "nr_isolated_anon 0", "nr_isolated_file 0", "workingset_nodes 0",
            "workingset_refault_anon 0", "workingset_refault_file 0", "workingset_activate_anon 0",
            "workingset_activate_file 0", "workingset_restore_anon 0", "workingset_restore_file 0",
            "workingset_nodereclaim 0", "nr_anon_pages 161660", "nr_mapped 157093", "nr_file_pages 594970",
            "nr_dirty 76", "nr_writeback 0", "nr_writeback_temp 0", "nr_shmem 37155", "nr_shmem_hugepages 0",
            "nr_shmem_pmdmapped 0", "nr_file_hugepages 0", "nr_file_pmdmapped 0", "nr_anon_transparent_hugepages 0",
            "nr_vmscan_write 0", "nr_vmscan_immediate_reclaim 0", "nr_dirtied 2310", "nr_written 2234",
            "nr_kernel_misc_reclaimable 0", "nr_foll_pin_acquired 0", "nr_foll_pin_released 0",
            "nr_kernel_stack 15536", "nr_page_table_pages 7024", "nr_swapcached 0", "nr_dirty_threshold 128227",
            "nr_dirty_background_threshold 64036", "pgpgin 1281580", "pgpgout 13684", "pswpin 0", "pswpout 0",
            "pgalloc_dma 0", "pgalloc_dma32 0", "pgalloc_normal 4138473", "pgalloc_movable 0", "pgfree 4282140",
            "pgactivate 309580", "pgdeactivate 0", "pgfault 4436549", "pgmajfault 20211", "pgrefill 0",
            "pgsteal_kswapd 0", "pgsteal_direct 0", "pgscan_kswapd 0", "pgscan_direct 0", "oom_kill 0",
            "compact_stall 0", "compact_fail 0", "compact_success 0", "unevictable_pgs_culled 0",
            "unevictable_pgs_scanned 0", "unevictable_pgs_rescued 0", "unevictable_pgs_mlocked 0",
            "unevictable_pgs_munlocked 0", "unevictable_pgs_cleared 0", "unevictable_pgs_stranded 0",
            "swap_ra 0", "swap_ra_hit 0",
        ).joinToString("\n", postfix = "\n"),
        "cap_last_cap" to "40\n",
    )
}
