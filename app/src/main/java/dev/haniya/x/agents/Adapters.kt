package dev.haniya.x.agents

import dev.haniya.x.core.Paths

/**
 * Where an adapter learns the newest released version. Fetched on the host (HTTP), cached for 6 h.
 * Facts verified against the live endpoints on 2026-11-15.
 */
sealed interface LatestSource {
    /** Plain-text body that is the version, e.g. downloads.claude.ai/claude-code-releases/latest → "2.1.325". */
    data class PlainText(val url: String) : LatestSource
    /** GitHub "releases/latest" redirect: Location ends with /tag/<prefix><version>. Avoids API rate limits. */
    data class GithubLatestRedirect(val url: String, val tagPrefix: String) : LatestSource
    /** JSON document with a top-level string field holding the version. */
    data class JsonField(val url: String, val field: String) : LatestSource
}

/**
 * Declarative description of one agent. Everything the manager, the screen and the launch sheet need
 * lives here; the install/update logic lives in assets/sdk/adapters/<id>.sh (copied to /opt/haniyax/adapters).
 */
class AgentAdapter(
    val info: AgentInfo,
    /** Command name looked up with `command -v` on the login-shell PATH (so user installs are found). */
    val binary: String,
    val versionArgs: String = "--version",
    val versionRegex: Regex = Semver.IN_TEXT,
    val latest: LatestSource,
    /** Major version HaniyaX was verified against; newer majors are offered as "未经验证". */
    val verifiedMajor: Int,
    /** Guest path prefixes of installs done by our installer (used with the marker to decide "haniyax" vs "user"). */
    val managedPrefixes: List<String>,
    /** Environment for every launch/detection (e.g. turning off self auto-update). */
    val env: Map<String, String> = emptyMap(),
    /** Arguments always passed before the session arguments. */
    val baseArgs: List<String> = emptyList(),
    /** Arguments that continue the most recent conversation in the current directory. */
    val continueArgs: List<String>,
    /** Arguments that resume one specific conversation. */
    val resumeArgs: (String) -> List<String>,
    /** Arguments that turn off the agent's OS sandbox; null = the agent has no such switch. */
    val fullAccessArgs: List<String>? = null,
    /** Shown under the full-access toggle (or alone when the agent has no sandbox switch). */
    val sandboxNote: String,
    /** Guest instruction file that receives the SDK hint block. */
    val hintFile: String,
    /** Config / history locations that installs and updates never touch (documentation for users). */
    val keptPaths: List<String>,
    /** Number of `::step::` lines the install script prints, for an overall progress estimate. */
    val installSteps: Int = 5,
) {
    val id: String get() = info.id

    /** Script run by Runtime.jobs; the SDK (incl. adapters) lives at /opt/haniyax inside the guest. */
    fun jobScript(subcommand: String, version: String? = null): String {
        val script = "${Paths.GUEST_SDK}/adapters/$id.sh"
        val v = version?.takeIf { Semver.parse(it) != null }
        return buildString {
            append("if [ ! -f ").append(script).append(" ]; then echo '::error:: 未找到适配脚本 ")
            append(script).append("，请在设置中修复工作区后重试'; exit 1; fi\n")
            append("bash ").append(script).append(' ').append(subcommand)
            if (v != null) append(' ').append(v)
            append('\n')
        }
    }

    /** Shell snippet that prints `hx:missing` or `hx:path=…`, `hx:real=…`, and the version output. */
    fun detectScript(): String = """
        export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.opencode/bin:${'$'}PATH"
        p="${'$'}(command -v $binary 2>/dev/null)"
        if [ -z "${'$'}p" ]; then echo "hx:missing"; exit 0; fi
        echo "hx:path=${'$'}p"
        echo "hx:real=${'$'}(readlink -f "${'$'}p" 2>/dev/null || echo "${'$'}p")"
        echo "hx:out-begin"
        timeout 50 "${'$'}p" $versionArgs </dev/null 2>&1 | head -n 8
        echo "hx:out-end"
    """.trimIndent()

    /**
     * Full launch command for an interactive terminal. [resume]: null = new conversation,
     * "" = continue the most recent one in the cwd, otherwise a session id.
     */
    fun command(resume: String?, fullAccess: Boolean): String {
        val args = buildList {
            add(binary)
            addAll(baseArgs)
            when {
                resume == null -> Unit
                resume.isEmpty() -> addAll(continueArgs)
                else -> addAll(resumeArgs(resume))
            }
            if (fullAccess && fullAccessArgs != null) addAll(fullAccessArgs)
        }
        // Env goes into the command string itself: TaskManager.resume() relaunches from the stored command only.
        val envPart = env.entries.joinToString("") { (k, v) -> "export $k=${shQuote(v)}; " }
        return PATH_PREFIX + envPart + args.joinToString(" ") { shQuote(it) }
    }

    /** Version string parsed from `<binary> --version` output, or null. */
    fun parseVersion(output: String): String? = Semver.extract(output, versionRegex)

    /** Whether the detected binary (after resolving symlinks) lives where our installer puts it. */
    fun isManagedPath(realPath: String?): Boolean =
        realPath != null && managedPrefixes.any { realPath.startsWith(it) }

    val supportsFullAccess: Boolean get() = fullAccessArgs != null

    companion object {
        /** Makes the installers' bin dirs visible even if the login profile does not add them. */
        const val PATH_PREFIX = "export PATH=\"\$HOME/.local/bin:\$HOME/.opencode/bin:\$PATH\"; "

        private val SAFE = Regex("^[A-Za-z0-9_./=:,@%+-]+$")

        /** POSIX single-quote escaping for one shell word. */
        fun shQuote(s: String): String =
            if (s.isNotEmpty() && SAFE.matches(s)) s else "'" + s.replace("'", "'\\''") + "'"

        /** Session ids we pass on the command line must look like ids (uuid, ses_…); anything else is rejected. */
        private val SESSION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
        fun isValidSessionId(id: String): Boolean = SESSION_ID.matches(id)
    }
}

object Adapters {
    private const val H = Paths.GUEST_HOME

    /**
     * Claude Code — official native installer (curl -fsSL https://claude.ai/install.sh | bash [-s <version>]).
     * Launcher ~/.local/bin/claude → ~/.local/share/claude/versions/<v>. Transcripts: ~/.claude/projects/<cwd with every
     * non-alphanumeric char → '-'>/<session-id>.jsonl (names > 200 chars are truncated + hashed). Latest version:
     * https://downloads.claude.ai/claude-code-releases/latest (the installer's own source; npm @anthropic-ai/claude-code
     * reports the same). DISABLE_AUTOUPDATER=1 stops background self-updates without editing settings files.
     * Runs fine as (fake) root in interactive mode; only --dangerously-skip-permissions refuses root, so we never pass it.
     * The Bash sandbox is off by default and falls back to unsandboxed commands when unavailable → no flag needed.
     */
    val claude = AgentAdapter(
        info = AgentInfo("claude", "Claude Code", "Anthropic", "brand:claude"),
        binary = "claude",
        latest = LatestSource.PlainText("https://downloads.claude.ai/claude-code-releases/latest"),
        verifiedMajor = 2,
        managedPrefixes = listOf("$H/.local/share/claude/versions/"),
        env = mapOf("DISABLE_AUTOUPDATER" to "1"),
        continueArgs = listOf("--continue"),
        resumeArgs = { listOf("--resume", it) },
        fullAccessArgs = null,
        sandboxNote = "Claude Code 的命令沙箱默认关闭，所有操作仍按权限审批进行；HaniyaX 不会跳过审批。",
        hintFile = "$H/.claude/CLAUDE.md",
        keptPaths = listOf("~/.claude", "~/.claude.json"),
    )

    /**
     * Codex — GitHub release rust-v<version>, asset codex-package-aarch64-unknown-linux-musl.tar.gz (bin/codex + rg +
     * bwrap, sha256 in codex-package_SHA256SUMS), fallback codex-aarch64-unknown-linux-musl.tar.gz. Installed under
     * ~/.local/share/haniyax/agents/codex/<v>/ with ~/.local/bin/codex swapped atomically.
     * Resume: `codex resume --last` (scoped to cwd) / `codex resume <id>`. Sandbox: `--sandbox danger-full-access`.
     * `-c check_for_update_on_startup=false`: updates are managed here (documented for centrally managed installs).
     */
    val codex = AgentAdapter(
        info = AgentInfo("codex", "Codex", "OpenAI", "brand:codex"),
        binary = "codex",
        latest = LatestSource.GithubLatestRedirect("https://github.com/openai/codex/releases/latest", "rust-v"),
        verifiedMajor = 0,
        managedPrefixes = listOf("$H/.local/share/haniyax/agents/codex/"),
        baseArgs = listOf("-c", "check_for_update_on_startup=false"),
        continueArgs = listOf("resume", "--last"),
        resumeArgs = { listOf("resume", it) },
        fullAccessArgs = listOf("--sandbox", "danger-full-access"),
        sandboxNote = "工作区运行在 proot 中，Codex 的系统沙箱（bubblewrap / Landlock）通常无法启动，命令会执行失败或反复请求授权。" +
            "开启后以 --sandbox danger-full-access 启动：Codex 可读写工作区内的任何文件并访问网络，审批策略仍然有效。只影响之后新开的会话。",
        hintFile = "$H/.codex/AGENTS.md",
        keptPaths = listOf("~/.codex"),
        installSteps = 6,
    )

    /**
     * Antigravity CLI (Google) — command `agy`. Release manifest of the official installer
     * (https://antigravity.google/cli/install.sh): <base>/manifests/linux_arm64.json → version / url / sha512.
     * Installed by assets/sdk/adapters/antigravity.sh under ~/.local/share/haniyax/agents/antigravity/<v>/agy with
     * ~/.local/bin/agy switched atomically. Docs (antigravity.google/docs/cli/reference): `--continue` resumes the latest
     * conversation of the cwd, `--conversation <id>` a specific one; AGY_CLI_DISABLE_AUTO_UPDATE=true stops self-update.
     * No OS sandbox unless `--sandbox` is passed (off by default). Global rules: ~/.gemini/AGENTS.md.
     */
    val antigravity = AgentAdapter(
        info = AgentInfo("antigravity", "Antigravity", "Google", "brand:antigravity"),
        binary = "agy",
        latest = LatestSource.JsonField(
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json", "version",
        ),
        verifiedMajor = 1,
        managedPrefixes = listOf("$H/.local/share/haniyax/agents/antigravity/"),
        env = mapOf("AGY_CLI_DISABLE_AUTO_UPDATE" to "true"),
        continueArgs = listOf("--continue"),
        resumeArgs = { listOf("--conversation", it) },
        fullAccessArgs = null,
        sandboxNote = "Antigravity 默认不启用终端沙箱，操作按它自己的权限设置审批。",
        hintFile = "$H/.gemini/AGENTS.md",
        keptPaths = listOf("~/.gemini"),
    )

    /**
     * OpenCode v2 — official installer https://opencode.ai/v2/install (→ ~/.opencode/bin/opencode, npm package
     * @opencode/cli-linux-arm64). Latest: https://opencode.ai/update/api/latest/cli/npm (.version). Resume:
     * `opencode --continue` / `opencode --session <id>`; sessions: `opencode session list --format json`.
     * OPENCODE_DISABLE_AUTOUPDATE=1 skips the startup update check (packages/cli/src/services/updater.ts, v2.3.0).
     * `--continue` picks the newest session whose location directory equals the cwd (session-target.ts).
     * `--standalone` runs a private server owned by the terminal process instead of a detached shared background
     * service, which would otherwise outlive the tab and keep the proot session alive. OpenCode has no OS sandbox.
     */
    val opencode = AgentAdapter(
        info = AgentInfo("opencode", "OpenCode", "Anomaly", "brand:opencode"),
        binary = "opencode",
        latest = LatestSource.JsonField("https://opencode.ai/update/api/latest/cli/npm", "version"),
        verifiedMajor = 2,
        managedPrefixes = listOf("$H/.opencode/bin/"),
        env = mapOf("OPENCODE_DISABLE_AUTOUPDATE" to "1"),
        baseArgs = listOf("--standalone"),
        continueArgs = listOf("--continue"),
        resumeArgs = { listOf("--session", it) },
        fullAccessArgs = null,
        sandboxNote = "OpenCode 没有系统沙箱，操作按它自己的权限规则审批。",
        hintFile = "$H/.config/opencode/AGENTS.md",
        keptPaths = listOf("~/.config/opencode", "~/.local/share/opencode"),
    )

    val all: List<AgentAdapter> = listOf(claude, codex, antigravity, opencode)

    fun get(id: String): AgentAdapter = all.firstOrNull { it.id == id } ?: throw IllegalArgumentException("未知的智能体：$id")
    fun find(id: String?): AgentAdapter? = all.firstOrNull { it.id == id }

    /** Icon for a task or row about [agentId]: the agent's product logo, or a generic robot. */
    fun iconOf(agentId: String?): String = find(agentId)?.info?.icon?.ifBlank { null } ?: "bot"
}

/** Result of running [AgentAdapter.detectScript]. */
sealed interface Detection {
    data object Missing : Detection
    data class Found(val path: String, val realPath: String?, val version: String?, val output: String) : Detection
}

object DetectParser {
    fun parse(stdout: String, regex: Regex = Semver.IN_TEXT): Detection? {
        val lines = stdout.lines()
        if (lines.any { it.trim() == "hx:missing" }) return Detection.Missing
        val path = lines.firstOrNull { it.startsWith("hx:path=") }?.removePrefix("hx:path=")?.trim()?.ifEmpty { null } ?: return null
        val real = lines.firstOrNull { it.startsWith("hx:real=") }?.removePrefix("hx:real=")?.trim()?.ifEmpty { null }
        val b = lines.indexOfFirst { it.trim() == "hx:out-begin" }
        val e = lines.indexOfFirst { it.trim() == "hx:out-end" }
        val out = if (b >= 0) lines.subList(b + 1, if (e > b) e else lines.size).joinToString("\n").trim() else ""
        return Detection.Found(path, real, Semver.extract(out, regex), out)
    }

    /** Version from the "latest" endpoints. */
    fun latestFromPlain(body: String): String? = Semver.extract(body.trim())?.takeIf { Semver.parse(it) != null }

    fun latestFromLocation(location: String?, tagPrefix: String): String? {
        val tag = location?.trim()?.substringAfterLast("/tag/", "")?.ifEmpty { null } ?: return null
        val v = java.net.URLDecoder.decode(tag, "UTF-8").removePrefix(tagPrefix)
        return v.takeIf { Semver.parse(it) != null }
    }

    fun latestFromJson(body: String, field: String): String? {
        val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val v = (el as? kotlinx.serialization.json.JsonObject)?.get(field)
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return v?.trim()?.takeIf { Semver.parse(it) != null }
    }
}
