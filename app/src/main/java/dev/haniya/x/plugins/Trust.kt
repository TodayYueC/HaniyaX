package dev.haniya.x.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Trust classification, the exact command list and the approval hash. Pure JVM code (unit tested).
 *
 * DisplayOnly  = no `command` sources and no `command` / `terminal` / `service` actions.
 * RunsCommands = anything else. Such a plugin runs nothing until the user approves the exact command
 *                list (after param substitution) in the app; any change to it invalidates the approval.
 */
object Trust {
    fun classify(m: Manifest): PluginTrust {
        val cmdSource = m.sources.values.any { it is SourceDef.Command }
        val cmdAction = m.actions.any { it is ActionDef.Command || it is ActionDef.Terminal || it is ActionDef.Service }
        return if (cmdSource || cmdAction) PluginTrust.RunsCommands else PluginTrust.DisplayOnly
    }

    /** Every command the plugin can run, rendered with the current params (shell-quoted). Sorted by [CommandEntry.where]. */
    fun commands(m: Manifest, params: Map<String, JsonPrimitive>): List<CommandEntry> {
        val ctx = TemplateContext(params = params, paramTypes = m.params.mapValues { it.value.type })
        val out = ArrayList<CommandEntry>()
        for (s in m.sources.values) if (s is SourceDef.Command) {
            out.add(CommandEntry("sources.${s.name}", "command", Templates.render(s.run, ctx, shell = true), s.cwd?.let { cwd(it, ctx) }))
        }
        for (a in m.actions) when (a) {
            is ActionDef.Command -> out.add(CommandEntry("actions.${a.id}", "command", Templates.render(a.run, ctx, shell = true), a.cwd?.let { cwd(it, ctx) }))
            is ActionDef.Terminal -> out.add(CommandEntry("actions.${a.id}", "terminal", Templates.render(a.run, ctx, shell = true), a.cwd?.let { cwd(it, ctx) }))
            is ActionDef.Service -> out.add(CommandEntry("actions.${a.id}", "service", "${a.op.id} ${a.name}", null))
            else -> Unit
        }
        return out.sortedBy { it.where }
    }

    /** A cwd is passed to the process launcher, not to a shell: substitute params without quoting. */
    fun cwd(t: String, ctx: TemplateContext): String = ParamValues.expandHome(Templates.render(t, ctx, shell = false))

    /** SHA-256 (hex) over the canonical command list, bound to the plugin id. */
    fun hash(id: String, commands: List<CommandEntry>): String {
        val canon = buildString {
            append("hx-approval-v1\n").append(id).append('\n')
            for (c in commands.sortedBy { it.where }) {
                append(JsonArray(listOf(JsonPrimitive(c.where), JsonPrimitive(c.kind), JsonPrimitive(c.cwd ?: ""), JsonPrimitive(c.run))).toString())
                append('\n')
            }
        }
        val d = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * Params in effect: local.json values (coerced to the declared type) over manifest defaults.
     * Returns values, the names of params that still have no value, and problems with local values.
     */
    fun effectiveParams(m: Manifest, local: Map<String, JsonElement>): EffectiveParams {
        val values = LinkedHashMap<String, JsonPrimitive>()
        val missing = ArrayList<String>()
        val problems = ArrayList<String>()
        for ((k, def) in m.params) {
            val lv = local[k]
            val coerced = lv?.let { ParamValues.coerce(it, def.type) }
            if (lv != null && coerced == null) problems.add("local.json params.$k：不是有效的 ${def.type.id}")
            val v = coerced ?: def.default
            if (v == null) missing.add(k) else values[k] = v
        }
        for (k in local.keys) if (k !in m.params) problems.add("local.json params.$k：plugin.json 中没有声明这个参数")
        return EffectiveParams(values, missing, problems)
    }

    /** Reads the `params` object of a local.json document. */
    fun localParams(localJson: JsonElement?): Map<String, JsonElement> =
        ((localJson as? JsonObject)?.get("params") as? JsonObject)?.toMap() ?: emptyMap()
}

data class CommandEntry(
    /** "sources.<name>" or "actions.<id>". */
    val where: String,
    /** "command" | "terminal" | "service". */
    val kind: String,
    /** Final command text after substitution (for service: "<op> <name>"). */
    val run: String,
    /** Guest working directory, if set. */
    val cwd: String?,
)

data class EffectiveParams(
    val values: Map<String, JsonPrimitive>,
    val missing: List<String>,
    val problems: List<String>,
)

enum class ApprovalState {
    /** DisplayOnly plugin. */
    NotRequired,
    Approved,
    /** Never approved. */
    Pending,
    /** Was approved, but the command list (or the params inside it) changed since. */
    Changed,
}

object ApprovalLogic {
    fun state(trust: PluginTrust, currentHash: String?, storedHash: String?): ApprovalState = when {
        trust == PluginTrust.DisplayOnly -> ApprovalState.NotRequired
        currentHash != null && storedHash == currentHash -> ApprovalState.Approved
        storedHash != null -> ApprovalState.Changed
        else -> ApprovalState.Pending
    }
}
