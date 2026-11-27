package dev.haniya.x.host

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** Launches only the APK-installed runner. The SDK and workspace are writable by guest processes. */
internal class HostRunner(private val context: Context) {
    private var stopped = false
    private var listener: LocalServerSocket? = null
    private var peer: LocalSocket? = null
    private var process: java.lang.Process? = null
    private val errors = StringBuilder()

    data class Channels(val input: DataInputStream, val output: DataOutputStream)
    private val lock = Any()

    fun connect(command: HostCommand, su: File?): Channels {
        val uid = if (command.root) 0 else Process.myUid()
        val name = "haniyax-runner-${UUID.randomUUID()}"
        val installed = File(context.applicationInfo.nativeLibraryDir, RUNNER)
        if (!installed.canExecute()) throw IOException("Installed Android host runner is missing")
        val exec = listOf(
            installed.path, name, uid.toString(), if (command.tty) "tty" else "pipe",
            command.geometry.toUInt().toString(), (command.timeoutMs + 2_000).toString(),
        ) + command.nativeArgs
        val args = if (command.root) {
            val binary = su ?: throw IOException("No supported Android su binary found")
            listOf(binary.path, "-c", "exec " + exec.joinToString(" ", transform = ::quote))
        } else exec
        val (server, child) = synchronized(lock) {
            if (stopped) throw IOException("Android host request canceled")
            val bound = LocalServerSocket(name)
            listener = bound
            val started = ProcessBuilder(args).directory(File("/"))
                .redirectErrorStream(true).apply {
                    environment().apply {
                        clear()
                        put("PATH", "/system/bin:/system/xbin")
                        put("ANDROID_ROOT", "/system")
                        put("ANDROID_DATA", "/data")
                    }
                }.start()
            process = started
            bound to started
        }
        runCatching { child.outputStream.close() }
        thread(name = "haniyax-host-native-launch", isDaemon = true) {
            try {
                child.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(512)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(errors) { errors.append(buffer, 0, minOf(count, 1024 - errors.length)) }
                    }
                }
            } catch (_: IOException) {
                // A canceled su launch can close its diagnostic pipe.
            } finally {
                closeListener(server)
            }
        }
        val local = try {
            server.accept()
        } catch (e: IOException) {
            val detail = synchronized(errors) { errors.toString().trim() }
            throw IOException(detail.ifBlank {
                if (command.root) "Android su did not start an authenticated UID 0 runner" else "Android runner did not connect"
            }, e)
        }
        try {
            synchronized(lock) {
                if (stopped || local.peerCredentials.uid != uid) throw IOException("Android runner UID verification failed")
                peer = local
            }
            return Channels(DataInputStream(local.inputStream), DataOutputStream(local.outputStream))
        } catch (e: Exception) {
            closeSocket(local)
            throw e
        } finally {
            closeListener(server)
        }
    }

    fun stop() {
        val open = synchronized(lock) {
            if (stopped) return
            stopped = true
            Triple(listener, peer, process)
        }
        open.first?.let(::closeListener)
        // Closing the control socket is the cancellation signal to the native subreaper.
        // Keep the runner alive to reap children. A launcher still waiting for su is safe to kill.
        open.second?.let(::closeSocket)
        if (open.second == null) runCatching { open.third?.destroyForcibly() }
    }

    private fun closeListener(server: LocalServerSocket) {
        synchronized(lock) {
            if (listener !== server) return
            listener = null
        }
        runCatching { Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { server.close() }
    }

    companion object {
        private const val RUNNER = "libhaniyax_host_runner.so"
        const val CLI = "libhaniyax_host_cli.so"

        fun findSu(): File? = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/vendor/bin/su")
            .map(::File).firstOrNull { it.canExecute() }

        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

        fun closeSocket(socket: LocalSocket) {
            runCatching { socket.shutdownOutput() }
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
        }
    }
}
