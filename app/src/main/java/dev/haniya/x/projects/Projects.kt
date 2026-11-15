package dev.haniya.x.projects

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** CONTRACT — owned by the home module (ProjectStoreImpl). Persisted in Paths.state/projects.json. */
@Serializable
data class Project(
    /** Guest absolute path, e.g. /root/projects/web. */
    val path: String,
    val name: String,
    val lastOpenedAt: Long = 0,
    val pinned: Boolean = false,
    /** Agent id last used in this project. */
    val lastAgent: String? = null,
)

interface ProjectsApi {
    /** Pinned first, then most recently opened. Also includes git repos discovered under /root/projects. */
    val projects: StateFlow<List<Project>>
    fun touch(path: String, agentId: String? = null)
    fun add(path: String): Project
    fun remove(path: String)
    fun setPinned(path: String, pinned: Boolean)
    /** Creates /root/projects/<name> (optionally `git clone url`) and returns its path. */
    suspend fun create(name: String, gitUrl: String? = null): Result<String>
    /** Rescans /root/projects. */
    fun refresh()
}
