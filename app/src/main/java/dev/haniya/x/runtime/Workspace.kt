package dev.haniya.x.runtime

import kotlinx.coroutines.flow.StateFlow

/** Result of a non-interactive command run inside the guest. */
data class ExecResult(val code: Int, val stdout: String, val stderr: String) {
    val ok get() = code == 0
}

/** Everything needed to exec a process (via proot) on the host: argv[0] included in [args]. */
data class LaunchSpec(val executable: String, val args: Array<String>, val env: Array<String>, val hostCwd: String)

sealed interface WorkspaceState {
    data object NotInstalled : WorkspaceState
    /** [progress] in 0..1 or null if indeterminate; [step] is a short human label (Chinese). */
    data class Installing(val step: String, val progress: Float?, val log: List<String>) : WorkspaceState
    data object Ready : WorkspaceState
    data class Failed(val message: String, val log: List<String>) : WorkspaceState
}

/**
 * The single Linux workspace (proot + Debian). CONTRACT — public signatures are fixed;
 * the runtime module implements the bodies in WorkspaceImpl.kt and exposes it as [Workspace].
 */
interface WorkspaceApi {
    val state: StateFlow<WorkspaceState>

    /** Unpacks runtime + rootfs, configures DNS/hosts/locale, installs base apt packages, installs the SDK
     *  under /opt/haniyax. Idempotent; safe to call again after failure. Progress via [state]. */
    suspend fun install()

    /** Builds a proot launch for [argv] (guest argv, argv[0] = program) in guest dir [cwd]. The guest env
     *  is clean (HOME, PATH, TERM, LANG, COLORTERM, HX_* …) plus [extraEnv]. */
    fun launch(argv: List<String>, cwd: String = dev.haniya.x.core.Paths.GUEST_HOME, extraEnv: Map<String, String> = emptyMap()): LaunchSpec

    /** Login shell launch used for interactive terminals (`bash -l`, or `bash -lc <command>` then a shell). */
    fun shellLaunch(cwd: String, command: String? = null, extraEnv: Map<String, String> = emptyMap()): LaunchSpec

    /** Runs `bash -lc script` non-interactively and captures output. Never throws for non-zero exit. */
    suspend fun exec(script: String, cwd: String = dev.haniya.x.core.Paths.GUEST_HOME, timeoutMs: Long = 60_000, extraEnv: Map<String, String> = emptyMap()): ExecResult

    /** Starts `bash -lc script` as a background process (no pty), stdout+stderr appended to [hostLogFile]. */
    fun spawn(script: String, cwd: String, hostLogFile: java.io.File, extraEnv: Map<String, String> = emptyMap()): Process
}
