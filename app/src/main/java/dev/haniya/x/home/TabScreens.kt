package dev.haniya.x.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.x.App
import dev.haniya.x.agents.AgentLaunchSheet
import dev.haniya.x.agents.LaunchRequest
import dev.haniya.x.projects.Project
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.services.ServiceManager
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Page
import dev.haniya.x.ui.Route

/** 项目 tab: every project, with the project sheet, new / clone, and the file manager one tap away. */
@Composable
fun ProjectsScreen() {
    val projects by App.projects.projects.collectAsState()
    val tasks by Runtime.tasks.tasks.collectAsState()
    val layout by HomeStore.layout.collectAsState()
    var projectPath by rememberSaveable { mutableStateOf<String?>(null) }
    var newProject by rememberSaveable { mutableStateOf(false) }
    var launch by remember { mutableStateOf<LaunchRequest?>(null) }
    val agentName: (String) -> String = { id -> App.agents.agents.firstOrNull { it.id == id }?.name ?: id }

    LaunchedEffect(Unit) { App.projects.refresh() }

    Page(
        title = "项目",
        caption = "可以选择工作区或手机存储中的文件夹作为项目",
        actions = { IconCircleButton("folder", "文件", { Nav.go(Route.Files()) }) },
        overlay = {
            val project: Project? = projectPath?.let { path ->
                projects.firstOrNull { it.path == path } ?: Project(path = path, name = Fmt.folderName(path))
            }
            ProjectSheet(
                project = project, tasks = tasks, start = layout.start,
                onDismiss = { projectPath = null },
                onLaunchAgent = { launch = it },
            )
            NewProjectSheet(
                visible = newProject,
                onDismiss = { newProject = false },
                onCreated = { path -> newProject = false; projectPath = path },
            )
            AgentLaunchSheet(launch) { launch = null }
        },
    ) {
        ProjectsModule(
            projects, agentName, Modifier.fillMaxWidth(),
            onOpen = { projectPath = it.path },
            onNew = { newProject = true },
            limit = Int.MAX_VALUE,
            title = "全部项目",
        )
    }
}

/** 服务 tab: background services and their logs. */
@Composable
fun ServicesScreen() {
    val services by App.services.services.collectAsState()
    val errors by remember { ServiceManager.errorsOf(App.services) }.collectAsState()
    Page(title = "服务", caption = "后台服务与日志，随时查看与启停") {
        ServicesModule(services, errors, Modifier.fillMaxWidth())
    }
}
