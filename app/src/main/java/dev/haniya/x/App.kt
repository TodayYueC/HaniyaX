package dev.haniya.x

import dev.haniya.x.agents.AgentsApi
import dev.haniya.x.plugins.PluginsApi
import dev.haniya.x.projects.ProjectsApi
import dev.haniya.x.services.ServicesApi
import dev.haniya.x.settings.SettingsApi

/**
 * Feature singletons wired in HaniyaApp.onCreate. CONTRACT — fields fixed; every module registers its
 * implementation here. Runtime singletons live in dev.haniya.x.runtime.Runtime.
 */
object App {
    lateinit var context: android.content.Context
    lateinit var settings: SettingsApi
    lateinit var projects: ProjectsApi
    lateinit var services: ServicesApi
    lateinit var agents: AgentsApi
    lateinit var plugins: PluginsApi
    /** App-wide coroutine scope (SupervisorJob + Dispatchers.Default). */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
}
