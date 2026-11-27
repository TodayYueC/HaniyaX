package dev.haniya.x.host

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Environment
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Bind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

enum class HostRoot { Unchecked, Missing, Detected, Checking, Granted, Denied }

data class HostAccess(
    val available: Boolean = false,
    val mode: HostMode = HostMode.Disabled,
    val policy: HostPolicy = HostPolicy.Manual,
    val root: HostRoot = HostRoot.Unchecked,
    val uid: Int = Process.myUid(),
    val active: Int = 0,
)

data class HostSession(val id: String, val root: Boolean, val operation: String)

data class HostApproval(val id: String, val root: Boolean, val command: String)

data class HostRecord(
    val at: Long,
    val command: String,
    val root: Boolean,
    val result: String,
    val exit: Int,
)

class HostBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("android-host", Context.MODE_PRIVATE)
    private val _access = MutableStateFlow(HostAccess(policy = HostPolicy.entries.firstOrNull {
        it.id == prefs.getString("policy", null)
    } ?: HostPolicy.Manual))
    val access = _access.asStateFlow()
    private val _approvals = MutableStateFlow<List<HostApproval>>(emptyList())
    val approvals = _approvals.asStateFlow()
    private val _sessions = MutableStateFlow<List<HostSession>>(emptyList())
    val activeSessions = _sessions.asStateFlow()
    private val _records = MutableStateFlow(readRecords())
    val records = _records.asStateFlow()
    private val lock = Any()
    private val clock = ScheduledThreadPoolExecutor(2).apply { removeOnCancelPolicy = true }
    private val endpoint = "haniyax-host-${UUID.randomUUID()}"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val token = key.toByteArray(Charsets.US_ASCII)
    private val descriptor = publishDescriptor()

    private var server: LocalServerSocket? = null
    private var probing: HostRunner? = null
    private val clients = mutableSetOf<LocalSocket>()
    private val sessions = linkedMapOf<String, Session>()

    /** Bind only this descriptor outside the guest. Tools can use the CLI with a clean environment. */
    internal fun workspaceBind(): Bind? = descriptor
        ?.takeIf { Paths.host(Paths.GUEST_HOST_BRIDGE).isFile }
        ?.let { Bind(it.path, "${Paths.GUEST_HOST_BRIDGE}!") }

    private fun publishDescriptor(): File? = runCatching {
        val parent = context.noBackupFilesDir
        val destination = File(parent, "android-host-bridge")
        val temp = File.createTempFile(".host-bridge-", null, parent)
        try {
            Os.chmod(temp.path, 0x180) // 0600 before writing the credential
            temp.writeText("$endpoint\n$key\n")
            Os.rename(temp.path, destination.path)
            destination
        } finally {
            temp.delete()
        }
    }.onFailure { Log.w(TAG, "Cannot publish Android host connection descriptor", it) }.getOrNull()

    fun start() {
        if (descriptor == null) return
        val listening = synchronized(lock) {
            if (server != null) return
            try {
                LocalServerSocket(endpoint).also {
                    server = it
                    _access.value = _access.value.copy(available = true)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Cannot bind Android host bridge", e)
                return
            }
        }
        thread(name = "haniyax-host-broker", isDaemon = true) {
            try {
                while (true) {
                    val socket = listening.accept()
                    val accepted = synchronized(lock) {
                        server === listening && clients.size < MAX_CLIENTS && clients.add(socket)
                    }
                    if (!accepted) HostRunner.closeSocket(socket)
                    else thread(name = "haniyax-host-client", isDaemon = true) { serve(socket) }
                }
            } catch (e: IOException) {
                if (synchronized(lock) { server === listening }) {
                    Log.w(TAG, "Android host listener exited", e)
                    stop()
                }
            }
        }
    }

    fun stop() {
        val open = synchronized(lock) {
            val listening = server
            server = null
            _access.value = _access.value.copy(available = false)
            listening to clients.toList()
        }
        disable()
        open.first?.let {
            runCatching { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { it.close() }
        }
        open.second.forEach(HostRunner::closeSocket)
    }

    fun enableNormal() = updateAccess {
        if (!it.available) it else it.copy(
            mode = HostMode.Normal, root = if (HostRunner.findSu() == null) HostRoot.Missing else HostRoot.Detected,
        )
    }

    fun disable() = updateAccess { it.copy(mode = HostMode.Disabled, root = HostRoot.Unchecked) }

    fun setPolicy(policy: HostPolicy) {
        if (policy == access.value.policy) return
        updateAccess { it.copy(policy = policy) }
        prefs.edit().putString("policy", policy.id).apply()
    }

    /**
     * Opting into UID 0 can show the device's root-manager prompt.
     * Both SO_PEERCRED and the runner check the actual host UID at every invocation.
     */
    fun enableRoot() {
        val su = HostRunner.findSu()
        val runner = HostRunner(context)
        synchronized(lock) {
            if (_access.value.mode != HostMode.Normal || probing != null) return
            if (su == null) {
                _access.value = _access.value.copy(root = HostRoot.Missing)
                return
            }
            probing = runner
            _access.value = _access.value.copy(root = HostRoot.Checking)
        }
        thread(name = "haniyax-host-probe", isDaemon = true) {
            val deadline = clock.schedule(runner::stop, 30, TimeUnit.SECONDS)
            val success = try {
                val probe = HostRequest.command(listOf("id"), true, 5, 0, sharedStorage)
                    .copy(nativeArgs = listOf("exec", "/system/bin/id", "-u"))
                val channels = runner.connect(probe, su)
                HostWire.write(channels.output, HostWire.empty(HostWire.START))
                var uid = ""
                while (true) {
                    val frame = HostWire.read(channels.input)
                    if (frame.type == HostWire.EXIT) break
                    if (frame.type == HostWire.OUT) uid = (uid + frame.payload.toString(Charsets.UTF_8)).take(16)
                }
                uid.trim() == "0"
            } catch (e: Exception) {
                Log.i(TAG, "Root probe failed: ${e.javaClass.simpleName}")
                false
            } finally {
                deadline.cancel(false)
                runner.stop()
            }
            val revoke = synchronized(lock) {
                if (probing !== runner) return@thread
                probing = null
                if (success) {
                    _access.value = _access.value.copy(mode = HostMode.Root, root = HostRoot.Granted)
                    revokeLocked("permission-changed")
                } else {
                    _access.value = _access.value.copy(root = HostRoot.Denied)
                    emptyList()
                }
            }
            revoke.forEach { it.abort("permission-changed") }
        }
    }

    fun decide(id: String, allow: Boolean) {
        synchronized(lock) {
            val session = sessions[id] ?: return
            if (_approvals.value.none { it.id == id }) return
            session.approved = allow
            _approvals.value = _approvals.value.filterNot { it.id == id }
            session.decision.countDown()
        }
    }

    fun cancel(id: String) {
        val target = synchronized(lock) {
            sessions[id]?.also { it.reason.compareAndSet(null, "canceled") }
        }
        target?.abort("canceled")
    }

    private fun updateAccess(change: (HostAccess) -> HostAccess) {
        val (probe, active) = synchronized(lock) {
            val updated = change(_access.value)
            if (updated == _access.value) return
            val probe = probing
            probing = null
            _access.value = if (updated.root == HostRoot.Checking) updated.copy(root = HostRoot.Detected) else updated
            probe to revokeLocked("permission-changed")
        }
        probe?.stop()
        active.forEach { it.abort("permission-changed") }
    }

    /** Mark sessions stale under the same lock that guards permission checks and runner startup. */
    private fun revokeLocked(reason: String): List<Session> {
        _approvals.value = emptyList()
        return sessions.values.toList().onEach { it.reason.compareAndSet(null, reason) }
    }

    private val sharedStorage: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    private fun serve(socket: LocalSocket) {
        var session: Session? = null
        var handshake = clock.schedule({ HostRunner.closeSocket(socket) }, 5, TimeUnit.SECONDS)
        try {
            if (socket.peerCredentials.uid != Process.myUid()) return
            val inStream = DataInputStream(socket.inputStream)
            val outStream = DataOutputStream(socket.outputStream)
            if (!HostWire.authenticate(inStream, token)) return
            val request = try {
                HostWire.request(inStream)
            } catch (_: IllegalArgumentException) {
                return fail(outStream, "Invalid Android host request", 2)
            }
            handshake.cancel(false)
            if (request.args.size == 1 && request.args[0] in setOf("status", "info") && !request.root) {
                val state = access.value
                val info = JSONObject()
                    .put("available", state.available).put("mode", state.mode.id).put("policy", state.policy.id)
                    .put("environment", "android-host").put("appUid", state.uid).put("root", state.root.name.lowercase())
                    .put("androidSdk", Build.VERSION.SDK_INT).put("androidRelease", Build.VERSION.RELEASE)
                    .put("activeSessions", state.active)
                HostWire.write(outStream, HostWire.text(HostWire.OUT, "$info\n"))
                HostWire.write(outStream, HostWire.exit(0))
                return
            }
            val command = try {
                HostRequest.command(request.args, request.root, request.seconds, request.geometry, sharedStorage)
            } catch (e: IllegalArgumentException) {
                return fail(outStream, e.message ?: "Invalid Android host command", 2)
            }
            val blocked = synchronized(lock) {
                val state = _access.value
                when {
                    !state.available || state.mode == HostMode.Disabled -> "Android host access is disabled in HaniyaX"
                    command.root && state.mode != HostMode.Root -> "Enable and verify Root access in HaniyaX"
                    sessions.size >= MAX_SESSIONS -> "Android host bridge is busy"
                    else -> {
                        session = Session(socket, command, command.needsApproval(state.policy)).also {
                            sessions[it.id] = it
                            if (it.confirm) _approvals.value += HostApproval(it.id, command.root, command.description)
                        }
                        publishSessions()
                        null
                    }
                }
            }
            if (blocked != null) return fail(outStream, blocked, 126)
            val active = checkNotNull(session)
            // A dedicated reader observes cancellation even while an approval or launch is pending.
            thread(name = "haniyax-host-input", isDaemon = true) { active.relayInput(inStream) }
            if (active.confirm) {
                handshake = clock.schedule({ active.abort("approval-expired") }, 90, TimeUnit.SECONDS)
                HostWire.write(outStream, HostWire.text(HostWire.NOTICE, "Approve this Android host request in HaniyaX (90 seconds).\n"))
                active.decision.await()
                handshake.cancel(false)
            }
            val canceled = active.reason.get()
            if (canceled != null) return finishAbort(outStream, active, canceled)
            if (!active.approved) {
                active.record("denied", 126)
                return fail(outStream, "Android host request denied", 126)
            }

            handshake = clock.schedule({ active.abort("launch-timeout") }, minOf(30_000, command.timeoutMs), TimeUnit.MILLISECONDS)
            val channels = active.runner.connect(command, if (command.root) HostRunner.findSu() else null)
            synchronized(lock) {
                if (active.reason.get() != null) throw IOException("Android host access revoked")
                HostWire.write(channels.output, HostWire.empty(HostWire.START))
            }
            handshake.cancel(false)
            handshake = clock.schedule({ active.abort("timeout") }, command.timeoutMs, TimeUnit.MILLISECONDS)
            while (true) {
                val frame = HostWire.read(channels.input)
                when (frame.type) {
                    HostWire.READY -> {
                        if (frame.payload.isNotEmpty() || !active.input.compareAndSet(null, channels.output)) {
                            throw IOException("Invalid runner ready frame")
                        }
                    }
                    HostWire.OUT, HostWire.ERR -> Unit
                    HostWire.EXIT -> {
                        if (frame.payload.size != 4 || frame.int() !in 0..255) throw IOException("Invalid runner exit")
                        val reason = active.reason.get()
                        if (reason != null) return finishAbort(outStream, active, reason)
                        active.record("finished", frame.int())
                        HostWire.write(outStream, frame)
                        return
                    }
                    else -> throw IOException("Invalid runner frame")
                }
                HostWire.write(outStream, frame)
            }
        } catch (e: IOException) {
            session?.let {
                val reason = it.reason.get()
                if (reason != null) {
                    val code = abortCode(reason)
                    it.record(reason, code)
                    runCatching { reportAbort(DataOutputStream(socket.outputStream), reason) }
                } else {
                    it.record("failed", 125)
                    runCatching {
                        fail(DataOutputStream(socket.outputStream), e.message ?: "Android host execution failed", 125)
                    }
                }
            }
        } catch (e: Exception) {
            session?.record("failed", 125)
            Log.w(TAG, "Android host request failed: ${e.javaClass.simpleName}")
        } finally {
            handshake.cancel(false)
            session?.let { done ->
                synchronized(lock) {
                    sessions.remove(done.id)
                    publishSessions()
                    _approvals.value = _approvals.value.filterNot { it.id == done.id }
                }
                done.runner.stop()
            }
            synchronized(lock) { clients.remove(socket) }
            HostRunner.closeSocket(socket)
        }
    }

    private fun publishSessions() {
        _sessions.value = sessions.values.map { it.snapshot }
        _access.value = _access.value.copy(active = sessions.size)
    }

    private fun abortCode(reason: String) =
        if (reason.endsWith("timeout") || reason == "approval-expired") 124 else 130

    private fun finishAbort(output: DataOutputStream, session: Session, reason: String) {
        session.record(reason, abortCode(reason))
        reportAbort(output, reason)
    }

    private fun reportAbort(output: DataOutputStream, reason: String) {
        val message = when (reason) {
            "launch-timeout" -> "Android host runner or Root authorization timed out"
            "approval-expired" -> "Android host approval expired"
            "timeout" -> "Android host command timed out"
            "permission-changed" -> "Android host permission changed and the session was revoked"
            else -> "Android host session canceled"
        }
        fail(output, message, abortCode(reason))
    }

    private fun fail(output: DataOutputStream, message: String, code: Int) {
        HostWire.write(output, HostWire.text(HostWire.ERR, "haniyax host: $message\n"))
        HostWire.write(output, HostWire.exit(code))
    }

    private inner class Session(val guest: LocalSocket, private val command: HostCommand, val confirm: Boolean) {
        val id: String = UUID.randomUUID().toString()
        val snapshot = HostSession(id, command.root, command.operation)
        val runner = HostRunner(context)
        val decision = CountDownLatch(1)
        @Volatile var approved = !confirm
        val reason = AtomicReference<String?>()
        val input = AtomicReference<DataOutputStream?>()
        private val logged = AtomicReference<HostRecord?>()

        fun abort(why: String) {
            reason.compareAndSet(null, why)
            decision.countDown()
            runner.stop()
            // Give the normal reply path a chance to return an exit status. A peer that stopped
            // reading cannot hold a bridge slot indefinitely.
            clock.schedule({ HostRunner.closeSocket(guest) }, 1, TimeUnit.SECONDS)
        }

        fun relayInput(from: DataInputStream) {
            try {
                while (true) {
                    val frame = HostWire.read(from)
                    val permitted = when (frame.type) {
                        HostWire.DATA -> frame.payload.isNotEmpty()
                        HostWire.EOF -> frame.payload.isEmpty()
                        HostWire.RESIZE -> command.tty && frame.payload.size == 4
                        else -> false
                    }
                    val to = input.get()
                    if (!permitted || to == null) break
                    if (command.expectsInput) HostWire.write(to, frame)
                }
            } catch (_: IOException) {
                // Closing stdin (HX_EOF) is a frame. A transport EOF cancels the command.
            }
            abort("disconnected")
        }

        fun record(result: String, code: Int) {
            // Deliberately keep the operation name only: no shell text, file names, payloads or output.
            val item = HostRecord(System.currentTimeMillis(), command.operation, command.root, result, code)
            if (logged.compareAndSet(null, item)) appendRecord(item)
        }
    }

    private fun readRecords(): List<HostRecord> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]") ?: "[]")
        List(minOf(array.length(), HISTORY_SIZE)) { index ->
            val item = array.getJSONObject(index)
            HostRecord(item.getLong("at"), item.getString("command"), item.getBoolean("root"),
                item.getString("result"), item.getInt("exit"))
        }
    }.getOrDefault(emptyList())

    private fun appendRecord(record: HostRecord) = synchronized(lock) {
        val list = (listOf(record) + _records.value).take(HISTORY_SIZE)
        _records.value = list
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().put("at", item.at).put("command", item.command).put("root", item.root)
                .put("result", item.result).put("exit", item.exit))
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private companion object {
        const val TAG = "HaniyaX.Host"
        const val MAX_CLIENTS = 16
        const val MAX_SESSIONS = 6
        const val HISTORY_SIZE = 60
    }
}
