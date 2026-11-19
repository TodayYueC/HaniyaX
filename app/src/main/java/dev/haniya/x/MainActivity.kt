package dev.haniya.x

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.haniya.relief.component.AppBackground
import dev.haniya.relief.component.LocalToast
import dev.haniya.relief.component.ToastHost
import dev.haniya.relief.component.ToastState
import androidx.compose.animation.core.snap
import dev.haniya.relief.theme.LocalReducedMotion
import dev.haniya.relief.theme.ReliefMotion
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.agents.AgentsScreen
import dev.haniya.x.dsh.DshScreen
import dev.haniya.x.files.FilesScreen
import dev.haniya.x.home.HomeScreen
import dev.haniya.x.home.StartRouter
import dev.haniya.x.plugins.PluginScreen
import dev.haniya.x.providers.ProvidersScreen
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.WorkspaceService
import dev.haniya.x.runtime.WorkspaceSetupScreen
import dev.haniya.x.runtime.WorkspaceState
import dev.haniya.x.services.ServiceScreen
import dev.haniya.x.settings.SettingsScreen
import dev.haniya.x.terminal.TerminalScreen
import dev.haniya.x.ui.HaniyaTheme
import dev.haniya.x.ui.Nav
import dev.haniya.x.ui.Route

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkspaceService.ensureStarted(this)
        setContent {
            HaniyaTheme {
                val c = ReliefTheme.colors
                LaunchedEffect(c.isDark) {
                    val style = if (c.isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                val toast = remember { ToastState() }
                CompositionLocalProvider(LocalToast provides toast, dev.haniya.relief.component.LocalLanguage provides dev.haniya.relief.i18n.I18n.state.value) {
                    AppBackground(Modifier.fillMaxSize()) {
                        Root()
                        ToastHost(toast)
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Notification taps carry EXTRA_TASK_ID to jump back into a task's tab. */
    private fun handleIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(WorkspaceService.EXTRA_TASK_ID) ?: return
        Runtime.tasks.focus(taskId)
        Nav.reset(Route.Terminal(taskId))
    }

    companion object
}

@Composable
private fun Root() {
    val ws by Runtime.workspace.state.collectAsState()
    if (ws !is WorkspaceState.Ready) {
        WorkspaceSetupScreen()
        return
    }
    StartRouter.ApplyOnce()
    BackHandler(enabled = Nav.stack.size > 1) { Nav.back() }
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = Nav.current,
        transitionSpec = {
            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
            else fadeIn(ReliefMotion.soft()) togetherWith fadeOut(ReliefMotion.soft())
        },
        label = "route",
    ) { route ->
        Box(Modifier.fillMaxSize()) {
            when (route) {
                Route.Home -> HomeScreen()
                is Route.Terminal -> TerminalScreen(route.taskId)
                Route.Agents -> AgentsScreen()
                is Route.Providers -> ProvidersScreen(route.agentId)
                Route.Dsh -> DshScreen()
                Route.Settings -> SettingsScreen()
                is Route.Plugin -> PluginScreen(route.pluginId)
                is Route.Service -> ServiceScreen(route.name)
                is Route.Files -> FilesScreen(route.path, route.pickProject)
                Route.Projects -> dev.haniya.x.home.ProjectsScreen()
                Route.Services -> dev.haniya.x.home.ServicesScreen()
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = Nav.isTabRoot && dev.haniya.relief.component.SheetTracker.open == 0,
        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        enter = fadeIn(), exit = fadeOut(),
    ) { MainTabBar(Modifier) }
    }
}

/** Floating relief bar: icon tabs plus the glazed round key, which opens the terminal. */
@Composable
private fun MainTabBar(modifier: Modifier) {
    val items = remember {
        listOf(
            dev.haniya.relief.component.NavItem("home", "首页", "home"),
            dev.haniya.relief.component.NavItem("projects", "项目", "folder"),
            dev.haniya.relief.component.NavItem("agents", "智能体", "bot"),
            dev.haniya.relief.component.NavItem("services", "服务", "server"),
            dev.haniya.relief.component.NavItem("settings", "设置", "sliders"),
        )
    }
    val selected = Nav.tabs.indexOf(Nav.current).coerceAtLeast(0)
    dev.haniya.relief.component.GlassBottomNav(
        items = items,
        selected = selected,
        onSelect = { Nav.selectTab(Nav.tabs[it]) },
        onQuickRecord = { Nav.go(Route.Terminal(null)) },
        quickIcon = "terminal",
        quickText = "终端",
        modifier = modifier
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.Companion.navigationBars)
            .padding(bottom = 12.dp),
    )
}
