package dev.haniya.x.services

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Background services (dev servers, DSH, …). Declared as JSON files in ~/.haniyax/services/<name>.json
 * inside the guest so any agent (or the `hx service` CLI) can add one. CONTRACT — owned by services module.
 */
@Serializable
data class ServiceDef(
    val name: String,
    /** bash -lc command. */
    val command: String,
    /** Guest cwd. */
    val cwd: String = "/root",
    /** Port it listens on (127.0.0.1), used for the health check and address display. */
    val port: Int? = null,
    /** Optional URL path for an HTTP health check, e.g. "/" or "/health". */
    val healthPath: String? = null,
    /** Guest log file; default ~/.haniyax/logs/<name>.log */
    val log: String? = null,
    val env: Map<String, String> = emptyMap(),
    val autostart: Boolean = false,
    /** Who created it ("user", "dsh", plugin id …). */
    val owner: String = "user",
    val description: String? = null,
)

enum class ServiceStatus { Stopped, Starting, Running, Unhealthy, Crashed }

data class ServiceState(
    val def: ServiceDef,
    val status: ServiceStatus,
    /** Managed by this app process (we hold the Process) vs. detected only by port. */
    val managed: Boolean,
    val pid: Int?,
    val startedAt: Long?,
    val address: String?,
    val lastExitCode: Int?,
)

interface ServicesApi {
    val services: StateFlow<List<ServiceState>>
    fun start(name: String)
    fun stop(name: String)
    fun restart(name: String)
    /** Last [lines] lines of the service log. */
    suspend fun tail(name: String, lines: Int = 200): List<String>
    /** Creates/updates the JSON definition file. */
    fun define(def: ServiceDef)
    fun remove(name: String)
    fun reload()
    /** Suspends until the service's port accepts connections or timeout. */
    suspend fun awaitPort(name: String, timeoutMs: Long = 30_000): Boolean
}
