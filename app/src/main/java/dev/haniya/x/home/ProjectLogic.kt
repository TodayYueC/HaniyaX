package dev.haniya.x.home

import dev.haniya.x.projects.Project

/** A directory found under /root/projects. */
data class ScannedDir(val path: String, val name: String, val mtime: Long)

/** Pure project rules: name / URL validation, list merging and git error messages (unit-tested). */
object ProjectLogic {
    const val MAX_NAME = 64
    private val NAME = Regex("^[\\p{L}\\p{N}._-]+$")
    private val URL_PREFIX = Regex("^(https?://|ssh://|git://|git@[^\\s:]+:)")

    /** Returns a Chinese error message, or null if [raw] is a usable folder name. */
    fun validateName(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> "请输入项目名称"
            name.length > MAX_NAME -> "名称太长，最多 $MAX_NAME 个字符"
            name.startsWith(".") -> "名称不能以点开头"
            name.startsWith("-") -> "名称不能以 - 开头"
            !NAME.matches(name) -> "名称只能包含字母、数字、中文、点、横线和下划线"
            else -> null
        }
    }

    fun validateGitUrl(raw: String): String? {
        val url = raw.trim()
        return when {
            url.isEmpty() -> null
            url.any { it.isWhitespace() } -> "Git 地址里不能有空格"
            !URL_PREFIX.containsMatchIn(url) -> "Git 地址应以 https://、ssh:// 或 git@ 开头"
            else -> null
        }
    }

    /** Folder name a clone of [url] would get, e.g. "https://x/a/b.git" → "b". */
    fun repoNameFromUrl(url: String): String? {
        val tail = url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        return tail.takeIf { it.isNotEmpty() && validateName(it) == null }
    }

    /** Single-quotes [s] for bash. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Normalizes a guest path: trimmed, absolute, no trailing slash. */
    fun normalizePath(path: String): String {
        val p = path.trim().replace(Regex("/+"), "/").trimEnd('/')
        return if (p.isEmpty()) "/" else if (p.startsWith("/")) p else "/$p"
    }

    /**
     * Persisted entries (kept when [exists]) ∪ scanned directories (newest first), minus [hidden];
     * then pinned first, most recently opened first. The sort is stable, so ties keep merge order.
     */
    fun merge(persisted: List<Project>, scanned: List<ScannedDir>, hidden: Set<String>, exists: (String) -> Boolean): List<Project> {
        val out = LinkedHashMap<String, Project>()
        persisted.forEach { p -> if (p.path !in hidden && exists(p.path)) out.putIfAbsent(p.path, p) }
        scanned.sortedByDescending { it.mtime }.forEach { d ->
            if (d.path !in hidden && d.path !in out) out[d.path] = Project(path = d.path, name = d.name)
        }
        return sort(out.values.toList())
    }

    fun sort(list: List<Project>): List<Project> =
        list.sortedWith(compareByDescending<Project> { it.pinned }.thenByDescending { it.lastOpenedAt })

    /** A useful Chinese reason for a failed `git clone`. */
    fun gitError(output: String, code: Int, timedOut: Boolean): String {
        val o = output
        fun has(vararg s: String) = s.any { o.contains(it, ignoreCase = true) }
        return when {
            timedOut -> "克隆超过 10 分钟仍未完成，已停止。大仓库可以在终端里克隆。"
            code == 127 || has("git: command not found", "git: not found") -> "工作区里还没有 git，请在终端运行 apt install git"
            has("Could not resolve host", "Temporary failure in name resolution", "Name or service not known") -> "无法解析主机名，请检查网络连接"
            has("Permission denied (publickey)") -> "SSH 密钥未被接受，请先在终端里配置 ~/.ssh"
            has("Host key verification failed") -> "无法验证主机密钥，请先在终端里连接一次该主机"
            has("could not read Username", "Authentication failed", "terminal prompts disabled", "could not read Password") ->
                "这个仓库需要登录。私有仓库请先在终端里配置凭据"
            has("Repository not found", "not found", "does not appear to be a git repository") -> "找不到这个仓库，请检查地址"
            has("already exists and is not an empty directory") -> "已有同名目录"
            has("No space left on device") -> "存储空间不足"
            has("Connection timed out", "Connection refused", "Failed to connect", "Could not connect", "Network is unreachable") ->
                "无法连接到 Git 服务器，请检查网络或代理"
            has("SSL certificate problem", "server certificate verification failed") -> "证书校验失败，请检查系统时间或代理设置"
            else -> {
                val line = o.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                    ?.removePrefix("fatal:")?.removePrefix("error:")?.trim()
                if (line.isNullOrEmpty()) "克隆失败（退出码 $code）" else "克隆失败：" + line.take(200)
            }
        }
    }
}
