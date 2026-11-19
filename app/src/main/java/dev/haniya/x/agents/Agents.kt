package dev.haniya.x.agents

import kotlinx.coroutines.flow.StateFlow

/** CONTRACT — owned by the agents module. */
data class AgentInfo(
    /** "claude", "codex", "antigravity", "opencode". */
    val id: String,
    val name: String,
    val vendor: String,
    val icon: String,
)

sealed interface AgentStatus {
    data object Unknown : AgentStatus
    data object Checking : AgentStatus
    data object NotInstalled : AgentStatus
    /** [latest] set when a newer version exists; [latestVerified] = inside the adapter's tested range;
     *  [source] = "haniyax" (installed by us) or "user" (found on PATH, installed by the user). */
    data class Installed(val version: String, val path: String, val source: String, val latest: String?, val latestVerified: Boolean) : AgentStatus
    /** A job (install/update) is running; observe it through Runtime.jobs. */
    data class Busy(val jobId: String, val kind: String) : AgentStatus
    data class Error(val message: String) : AgentStatus
}

/** A previous conversation an agent can resume in a given project. */
data class AgentSession(val id: String?, val title: String, val updatedAt: Long?)

interface AgentsApi {
    val agents: List<AgentInfo>
    val status: StateFlow<Map<String, AgentStatus>>
    fun refresh(agentId: String? = null)
    /** Starts an install job; returns job id. */
    fun install(agentId: String): String
    /** Starts an update job (user-initiated only). Config/login/history are never touched. Returns job id. */
    fun update(agentId: String): String
    /** Past sessions for [projectPath] (empty if unknown/unsupported). */
    suspend fun sessions(agentId: String, projectPath: String): List<AgentSession>
    /** Opens a real interactive terminal running the agent in [projectPath]. [resume]: null = new,
     *  "" = continue most recent, otherwise a session id. Returns task id. */
    fun launch(agentId: String, projectPath: String, resume: String? = null): String
    /** Running tasks of this agent (to warn before update). */
    fun runningTasks(agentId: String): List<String>
}
