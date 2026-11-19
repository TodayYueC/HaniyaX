package dev.haniya.x.agents

import android.util.Log
import dev.haniya.x.core.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Lets agent TUIs blend into the themed terminal instead of painting their own backgrounds.
 * OpenCode: its built-in "system" theme reads the terminal palette (OSC 4/10/11, answered by HaniyaX from the
 * active Relief palette) and follows its light/dark mode. Only applied when the user has not chosen a theme.
 */
object AgentThemes {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Pure: returns the new cli.json text, or null when nothing should change (theme already set / unparsable). */
    fun openCodeCliJson(current: String?): String? {
        if (current.isNullOrBlank()) return pretty.encodeToString(JsonElement.serializer(), systemTheme()) + "\n"
        val obj = runCatching { Json.parseToJsonElement(current) as? JsonObject }.getOrNull() ?: return null
        if (obj.containsKey("theme")) return null
        val out = LinkedHashMap<String, JsonElement>(obj)
        out["theme"] = systemTheme()["theme"]!!
        return pretty.encodeToString(JsonElement.serializer(), JsonObject(out)) + "\n"
    }

    private fun systemTheme() = buildJsonObject {
        put("theme", buildJsonObject { put("name", JsonPrimitive("system")); put("mode", JsonPrimitive("system")) })
    }

    fun ensureFor(agentId: String) {
        if (agentId != "opencode") return
        runCatching {
            val f = Paths.host("/root/.config/opencode/cli.json")
            val next = openCodeCliJson(f.takeIf { it.isFile }?.readText()) ?: return
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".cli.json.hx-tmp")
            tmp.writeText(next)
            if (!tmp.renameTo(f)) { f.writeText(next); tmp.delete() }
        }.onFailure { Log.w("HaniyaX.AgentThemes", "opencode theme", it) }
    }
}
