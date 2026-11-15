package dev.haniya.x.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Non-interactive, observable jobs (installs, updates, checks). Scripts may print protocol lines:
 *   `::step:: <label>`          start a named step (shown as a checklist)
 *   `::progress:: <0-100>`      progress of the current step
 *   `::error:: <message>`       a human-readable failure reason (shown prominently)
 *   `::result:: <text>`         final value (e.g. installed version)
 * All other lines are log output. CONTRACT — owned by runtime.
 */
enum class JobState { Running, Succeeded, Failed, Cancelled }

data class JobStep(val label: String, val done: Boolean, val failed: Boolean = false)

data class JobSnapshot(
    val id: String,
    val title: String,
    val state: JobState,
    val steps: List<JobStep>,
    val progress: Float?,
    val log: List<String>,          // last ~500 lines
    val error: String?,
    val result: String?,
    val exitCode: Int?,
)

interface JobsApi {
    /** All jobs since process start, newest first. */
    val jobs: StateFlow<List<JobSnapshot>>
    /** Starts `bash -lc script` in [cwd]; returns job id. [key] dedupes: a running job with the same key is returned instead. */
    fun start(title: String, script: String, key: String? = null, cwd: String = dev.haniya.x.core.Paths.GUEST_HOME, extraEnv: Map<String, String> = emptyMap()): String
    fun get(id: String): StateFlow<JobSnapshot?>
    fun runningForKey(key: String): JobSnapshot?
    fun cancel(id: String)
}
