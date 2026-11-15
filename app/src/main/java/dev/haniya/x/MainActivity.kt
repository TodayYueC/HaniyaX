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
import dev.haniya.relief.theme.Motion
import dev.haniya.relief.theme.ReliefTheme
import dev.haniya.x.agents.AgentsScreen
import dev.haniya.x.dsh.DshScreen
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
                CompositionLocalProvider(LocalToast provides toast) {
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
    AnimatedContent(
        targetState = Nav.current,
        transitionSpec = { fadeIn(Motion.soft()) togetherWith fadeOut(Motion.soft()) },
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
            }
        }
    }
}
