package dev.haniya.x.runtime

/** Process-wide singletons for the runtime layer. Implementations are set in HaniyaApp.onCreate. */
object Runtime {
    lateinit var workspace: WorkspaceApi
    lateinit var jobs: JobsApi
    lateinit var tasks: TasksApi
}
