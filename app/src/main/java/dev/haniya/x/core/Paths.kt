package dev.haniya.x.core

import android.content.Context
import java.io.File

/**
 * Filesystem layout. Host paths are real Android paths; guest paths are what processes see
 * inside the proot'd Debian workspace. CONTRACT — owned by the runtime module, signatures fixed.
 *
 * filesDir/
 *   runtime/   proot, loader, lib/ (bionic libs for proot)
 *   rootfs/    Debian arm64 root filesystem (guest "/")
 *   tmp/       PROOT_TMP_DIR
 *   state/     host-only app state (tasks.json, layout, …)
 */
object Paths {
    lateinit var files: File
        private set

    fun init(context: Context) { files = context.filesDir }

    val runtime get() = File(files, "runtime")
    val rootfs get() = File(files, "rootfs")
    val tmp get() = File(files, "tmp")
    val state get() = File(files, "state").apply { mkdirs() }

    /** Guest home directory. Processes run as (fake) root. */
    const val GUEST_HOME = "/root"
    /** Default directory for user projects inside the guest. */
    const val GUEST_PROJECTS = "/root/projects"
    /** HaniyaX's per-user data dir inside the guest (plugins, services, backups, token). */
    const val GUEST_HX = "/root/.haniyax"
    /** SDK, docs, adapters and the `hx` CLI shipped with the APK, inside the guest. */
    const val GUEST_SDK = "/opt/haniyax"

    /** Maps a guest absolute path to the host file backing it. */
    fun host(guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    /** Maps a host file under [rootfs] back to its guest path, or null if outside the rootfs. */
    fun guest(host: File): String? {
        val r = rootfs.canonicalPath; val h = host.canonicalPath
        return if (h == r) "/" else if (h.startsWith("$r/")) h.removePrefix(r) else null
    }

    val hxHost get() = host(GUEST_HX)
    val pluginsHost get() = File(hxHost, "plugins")
    val servicesHost get() = File(hxHost, "services")
    val backupsHost get() = File(hxHost, "backups")
}
