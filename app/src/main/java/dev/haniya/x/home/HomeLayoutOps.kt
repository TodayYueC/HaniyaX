package dev.haniya.x.home

/**
 * Pure operations on [HomeLayout] (unit-tested). Display order is "pinned first, then list order";
 * every operation returns a normalized layout whose module list is stored in that order.
 */
object HomeLayoutOps {
    const val PROJECTS = "projects"
    const val TASKS = "tasks"
    const val SERVICES = "services"
    val BUILTIN = listOf(PROJECTS, TASKS, SERVICES)
    const val PLUGIN_PREFIX = "plugin:"

    fun pluginModuleId(pluginId: String) = PLUGIN_PREFIX + pluginId

    fun pluginIdOf(moduleId: String): String? =
        if (moduleId.startsWith(PLUGIN_PREFIX)) moduleId.removePrefix(PLUGIN_PREFIX).takeIf { it.isNotEmpty() } else null

    /** Removes duplicate ids (first wins) and moves pinned modules to the front, keeping relative order. */
    fun normalize(modules: List<HomeModule>): List<HomeModule> {
        val seen = HashSet<String>()
        val unique = modules.filter { seen.add(it.id) }
        return unique.filter { it.pinned } + unique.filterNot { it.pinned }
    }

    fun normalized(layout: HomeLayout): HomeLayout {
        val m = normalize(layout.modules)
        return if (m == layout.modules) layout else layout.copy(modules = m)
    }

    /**
     * Brings the layout in line with the installed plugins: missing built-ins and new plugins are appended,
     * plugin modules whose plugin is gone ([pluginGone] = true and not in [pluginIds]) are dropped, unknown
     * ids are dropped. Plugins that are temporarily absent from [pluginIds] but still on disk keep their slot.
     */
    fun reconcile(layout: HomeLayout, pluginIds: List<String>, pluginGone: (String) -> Boolean): HomeLayout {
        val present = pluginIds.toSet()
        val kept = layout.modules.filter { m ->
            val pid = pluginIdOf(m.id)
            when {
                pid != null -> pid in present || !pluginGone(pid)
                else -> m.id in BUILTIN
            }
        }
        val ids = kept.mapTo(HashSet()) { it.id }
        val added = mutableListOf<HomeModule>()
        BUILTIN.forEach { if (ids.add(it)) added += HomeModule(it) }
        pluginIds.forEach { pid -> val id = pluginModuleId(pid); if (ids.add(id)) added += HomeModule(id) }
        val modules = normalize(kept + added)
        return if (modules == layout.modules) layout else layout.copy(modules = modules)
    }

    fun visible(layout: HomeLayout, available: (String) -> Boolean): List<HomeModule> =
        normalize(layout.modules).filter { !it.hidden && available(it.id) }

    fun hidden(layout: HomeLayout, available: (String) -> Boolean): List<HomeModule> =
        normalize(layout.modules).filter { it.hidden && available(it.id) }

    /** Swaps [id] with its visible neighbour ([delta] = -1 up, +1 down) inside the same pinned group. */
    fun move(layout: HomeLayout, id: String, delta: Int, available: (String) -> Boolean): HomeLayout {
        val all = normalize(layout.modules)
        val vis = all.filter { !it.hidden && available(it.id) }
        val i = vis.indexOfFirst { it.id == id }
        if (i < 0) return layout
        val j = i + delta
        if (j !in vis.indices || vis[j].pinned != vis[i].pinned) return layout
        val a = all.indexOfFirst { it.id == vis[i].id }
        val b = all.indexOfFirst { it.id == vis[j].id }
        val out = all.toMutableList()
        out[a] = all[b]; out[b] = all[a]
        return layout.copy(modules = out)
    }

    fun canMove(layout: HomeLayout, id: String, delta: Int, available: (String) -> Boolean): Boolean =
        move(layout, id, delta, available).modules != normalize(layout.modules)

    fun setHidden(layout: HomeLayout, id: String, hidden: Boolean): HomeLayout =
        layout.copy(modules = normalize(layout.modules.map { if (it.id == id) it.copy(hidden = hidden) else it }))

    fun setPinned(layout: HomeLayout, id: String, pinned: Boolean): HomeLayout =
        layout.copy(modules = normalize(layout.modules.map { if (it.id == id) it.copy(pinned = pinned) else it }))
}
