package dev.haniya.x

import android.app.Application
import dev.haniya.relief.i18n.I18n
import dev.haniya.x.agents.AgentManager
import dev.haniya.x.control.ControlServer
import dev.haniya.x.core.Paths
import dev.haniya.x.home.ProjectStore
import dev.haniya.x.plugins.PluginHost
import dev.haniya.x.runtime.JobsImpl
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskManager
import dev.haniya.x.runtime.WorkspaceImpl
import dev.haniya.x.services.ServiceManager
import dev.haniya.x.settings.SettingsStore

/**
 * Wiring point. Every module supplies the class named here (constructor shape fixed):
 *   runtime:  WorkspaceImpl(context), JobsImpl(), TaskManager(context)
 *   settings: SettingsStore()
 *   home:     ProjectStore()
 *   services: ServiceManager()
 *   agents:   AgentManager()
 *   plugins:  PluginHost()
 *   control:  ControlServer.start()  (localhost API for the `hx` CLI)
 */
class HaniyaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.context = this
        Paths.init(this)
        runCatching { I18n.init(this) }
        App.settings = SettingsStore()
        Runtime.workspace = WorkspaceImpl(this)
        Runtime.jobs = JobsImpl()
        Runtime.tasks = TaskManager(this)
        App.projects = ProjectStore()
        App.services = ServiceManager()
        App.agents = AgentManager()
        App.plugins = PluginHost()
        ControlServer.start()
        runCatching { dev.haniya.x.files.PhoneStorage.refresh(this) }
    }
}
