package dev.haniya.x.agents

import android.util.Log
import dev.haniya.x.core.Paths
import java.io.File
import java.io.IOException

/**
 * Keeps a short, marked pointer to the HaniyaX SDK in each agent's global instruction file
 * (~/.claude/CLAUDE.md, ~/.codex/AGENTS.md, ~/.config/opencode/AGENTS.md) while the setting
 * "agentSdkHints" is on, and removes it when it is off. Only the text between the markers is ever
 * written; everything else in those files is preserved byte for byte.
 */
object SdkHints {
    const val BEGIN = "<!-- haniyax:begin -->"
    const val END = "<!-- haniyax:end -->"

    val BODY: String = """
        ## HaniyaX 工作区
        你运行在 HaniyaX（Android 上的 Linux 开发工作台）中。首页卡片、后台服务等由 `hx` 命令行管理（`hx plugin …`、`hx service …`、`hx docs`）。
        在创建或修改首页卡片、登记后台服务之前，先阅读 /opt/haniyax/docs/README.md。
    """.trimIndent()

    val block: String get() = "$BEGIN\n$BODY\n$END"

    // ------------------------------------------------------------ pure text operations (unit tested)

    /** Inserts or replaces the marked block in [text]. Idempotent: applying twice yields the same text. */
    fun apply(text: String, block: String = this.block): String {
        val range = blockRange(text)
        if (range != null) {
            return text.substring(0, range.first) + block + text.substring(range.last + 1)
        }
        if (text.isEmpty()) return block + "\n"
        // Always add exactly one blank separator line of our own, so remove() can take it back without
        // eating a newline the user wrote (a missing final newline is the only thing normalised).
        val sep = if (text.endsWith("\n")) "\n" else "\n\n"
        return text + sep + block + "\n"
    }

    /** Removes the marked block (and the blank line we put before it). Returns [text] unchanged when absent. */
    fun remove(text: String): String {
        val range = blockRange(text) ?: return text
        var start = range.first
        var end = range.last + 1
        // Drop the newline that terminated our block.
        if (end < text.length && text[end] == '\n') end++
        // Drop the blank separator line apply() added, but never the user's own last newline.
        if (start >= 2 && text[start - 1] == '\n' && text[start - 2] == '\n') start--
        return text.substring(0, start) + text.substring(end)
    }

    fun contains(text: String): Boolean = blockRange(text) != null

    /** Index range (inclusive) of BEGIN … END, or null if there is no complete block. */
    private fun blockRange(text: String): IntRange? {
        val b = text.indexOf(BEGIN)
        if (b < 0) return null
        val e = text.indexOf(END, b + BEGIN.length)
        if (e < 0) return null
        return b until (e + END.length)
    }

    // ------------------------------------------------------------ files

    /** The instruction file the agent actually reads. Codex prefers a non-empty AGENTS.override.md. */
    fun effectiveFile(adapter: AgentAdapter): String {
        if (adapter.id == "codex") {
            val override = "${Paths.GUEST_HOME}/.codex/AGENTS.override.md"
            val f = Paths.host(override)
            if (f.isFile && f.length() > 0 && runCatching { f.readText().isNotBlank() }.getOrDefault(false)) return override
        }
        return adapter.hintFile
    }

    /** All files that may hold our block for [adapter] (so stale copies can be cleaned up). */
    private fun candidateFiles(adapter: AgentAdapter): List<String> =
        if (adapter.id == "codex") listOf(adapter.hintFile, "${Paths.GUEST_HOME}/.codex/AGENTS.override.md") else listOf(adapter.hintFile)

    /**
     * Brings every agent's instruction file in line with [enabled]. With [enabled], files are created only for agents
     * in [installed] (or whose config directory already exists), so nothing appears for agents the user never installed.
     * Blocking IO — call on Dispatchers.IO.
     */
    fun sync(enabled: Boolean, installed: Set<String>) {
        for (a in Adapters.all) {
            try {
                val target = effectiveFile(a)
                for (path in candidateFiles(a)) {
                    val f = Paths.host(path)
                    val want = enabled && path == target && (a.id in installed || f.parentFile?.isDirectory == true)
                    if (want) ensure(f) else clean(f)
                }
            } catch (e: IOException) {
                Log.w("SdkHints", "sync ${a.id} failed: ${e.message}")
            } catch (e: SecurityException) {
                Log.w("SdkHints", "sync ${a.id} failed: ${e.message}")
            }
        }
    }

    private fun ensure(f: File) {
        val old = if (f.isFile) f.readText() else ""
        val new = apply(old)
        if (new != old) writeAtomically(f, new)
    }

    private fun clean(f: File) {
        if (!f.isFile) return
        val old = f.readText()
        if (!contains(old)) return
        val new = remove(old)
        // The file only ever held our block: remove it rather than leave an empty file behind.
        if (new.isBlank()) f.delete() else writeAtomically(f, new)
    }

    private fun writeAtomically(f: File, text: String) {
        val dir = f.parentFile ?: throw IOException("无效路径：$f")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
        val tmp = File(dir, ".${f.name}.haniyax.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            f.writeText(text)
        }
    }
}
