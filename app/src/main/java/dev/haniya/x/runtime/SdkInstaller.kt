package dev.haniya.x.runtime

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import androidx.core.content.pm.PackageInfoCompat
import dev.haniya.x.core.Paths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Copies the APK's `assets/sdk/**` tree (hx CLI, docs, adapters, DSH plugin…) into the guest at
 * /opt/haniyax, replacing the previous copy, and stamps /opt/haniyax/VERSION with the APK identity so an
 * APK update refreshes it on the next start.
 */
object SdkInstaller {
    const val ASSET_ROOT = "sdk"
    private const val MODE_EXEC = 0x1ED // 0755
    private const val MODE_FILE = 0x1A4 // 0644
    private const val MODE_DIR = 0x1ED  // 0755

    val sdkDir: File get() = Paths.host(Paths.GUEST_SDK)
    private val versionFile: File get() = File(sdkDir, "VERSION")

    data class ApkIdentity(val versionName: String, val versionCode: Long, val lastUpdateTime: Long) {
        val stamp: String get() = "$versionCode:$lastUpdateTime"
    }

    fun apkIdentity(context: Context): ApkIdentity {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return ApkIdentity(pi.versionName ?: "", PackageInfoCompat.getLongVersionCode(pi), pi.lastUpdateTime)
    }

    /** Reads `stamp=` from an existing VERSION file; null when missing/unreadable. */
    fun installedStamp(): String? = try {
        versionFile.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("stamp=") }?.removePrefix("stamp=")?.trim()
    } catch (_: IOException) {
        null
    }

    fun needsRefresh(context: Context): Boolean = installedStamp() != apkIdentity(context).stamp

    /** True when this APK ships an sdk/ tree at all (other modules fill it; it may be absent). */
    fun hasBundledSdk(context: Context): Boolean = !context.assets.list(ASSET_ROOT).isNullOrEmpty()

    /**
     * Installs the SDK. Blocking IO; call on Dispatchers.IO. Returns the number of files copied.
     * Without a bundled sdk/ tree, still creates /opt/haniyax/bin and VERSION so PATH stays consistent.
     */
    fun install(context: Context, onLog: (String) -> Unit = {}): Int {
        val id = apkIdentity(context)
        val opt = sdkDir.parentFile ?: throw IOException("无效的 SDK 路径")
        opt.mkdirs()
        val staging = File(opt, ".haniyax.new")
        val old = File(opt, ".haniyax.old")
        RootfsExtractor.deleteTree(staging)
        RootfsExtractor.deleteTree(old)
        staging.mkdirs()
        val am = context.assets
        var copied = 0
        if (hasBundledSdk(context)) {
            copied = copyTree(am, ASSET_ROOT, staging, "")
        } else {
            onLog("此安装包未附带 SDK 文件，仅创建目录")
        }
        File(staging, "bin").mkdirs()
        File(staging, "VERSION").writeText(
            buildString {
                append("versionName=").append(id.versionName).append('\n')
                append("versionCode=").append(id.versionCode).append('\n')
                append("stamp=").append(id.stamp).append('\n')
                append("installedAt=").append(System.currentTimeMillis()).append('\n')
            },
        )
        chmodQuiet(File(staging, "VERSION"), MODE_FILE)
        chmodQuiet(staging, MODE_DIR)
        // Swap in: old -> .old, staging -> haniyax, drop .old.
        if (sdkDir.exists() || isSymlink(sdkDir)) {
            if (!sdkDir.renameTo(old)) RootfsExtractor.deleteTree(sdkDir)
        }
        if (!staging.renameTo(sdkDir)) throw IOException("无法替换 ${Paths.GUEST_SDK}")
        RootfsExtractor.deleteTree(old)
        onLog("HaniyaX 工具已更新（$copied 个文件，版本 ${id.versionName}）")
        return copied
    }

    private fun isSymlink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())

    /** Recursively copies asset dir [assetPath] into [dest]; [rel] is the path relative to the sdk root. */
    private fun copyTree(am: AssetManager, assetPath: String, dest: File, rel: String): Int {
        val children = am.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // Either a file or an empty directory (AssetManager can't tell them apart without opening).
            return try {
                am.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
                chmodQuiet(dest, if (isExecutable(rel)) MODE_EXEC else MODE_FILE)
                1
            } catch (_: FileNotFoundException) {
                dest.mkdirs(); chmodQuiet(dest, MODE_DIR); 0
            }
        }
        dest.mkdirs()
        chmodQuiet(dest, MODE_DIR)
        var n = 0
        for (name in children) {
            val childRel = if (rel.isEmpty()) name else "$rel/$name"
            n += copyTree(am, "$assetPath/$name", File(dest, name), childRel)
        }
        return n
    }

    /** Files under bin/ and shell scripts are executable. Pure; unit-tested. */
    fun isExecutable(relPath: String): Boolean =
        relPath.startsWith("bin/") || relPath.endsWith(".sh") || relPath.contains("/bin/")

    private fun chmodQuiet(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (_: Throwable) { }
    }
}
