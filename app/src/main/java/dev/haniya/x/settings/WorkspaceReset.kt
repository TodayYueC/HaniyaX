package dev.haniya.x.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.MainActivity
import dev.haniya.x.core.Paths
import dev.haniya.x.runtime.Runtime
import dev.haniya.x.runtime.TaskStatus
import dev.haniya.x.services.ServiceDefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * 重置工作区: ends every task and managed service, deletes rootfs + runtime + state/workspace.json and
 * restarts the app, which then creates a fresh workspace.
 */
object WorkspaceReset {
    sealed interface State {
        data object Idle : State
        data class Working(val step: String) : State
        /** Deletion finished; [problem] is set when some files could not be removed. */
        data class Done(val problem: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        if (_state.value is State.Working) return
        _state.value = State.Working("正在结束任务和服务…")
        App.scope.launch {
            val problem = try {
                stopEverything()
                _state.value = State.Working("正在删除工作区文件…")
                withContext(Dispatchers.IO) { deleteWorkspace() }
            } catch (e: Exception) {
                Log.w(TAG, "重置工作区失败", e)
                "重置时出错：${e.message ?: e.javaClass.simpleName}"
            }
            _state.value = State.Done(problem)
            // Clean result: relaunch straight away (the user is on the settings screen that started this).
            if (problem == null) withContext(Dispatchers.Main) { restartApp(App.context) }
        }
    }

    private suspend fun stopEverything() {
        App.hostBridge.disable()
        Runtime.tasks.tasks.value.filter { it.status == TaskStatus.Running }.forEach { runCatching { Runtime.tasks.terminate(it.id) } }
        App.services.services.value.filter(ServiceDefs::isManagedAlive).forEach { runCatching { App.services.stop(it.def.name) } }
        val deadline = System.currentTimeMillis() + STOP_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val tasksLeft = Runtime.tasks.tasks.value.any { it.status == TaskStatus.Running }
            val servicesLeft = App.services.services.value.any(ServiceDefs::isManagedAlive)
            if (!tasksLeft && !servicesLeft) break
            delay(200)
        }
    }

    /** Returns a Chinese problem description, or null when everything was removed. */
    private fun deleteWorkspace(): String? {
        // The record goes first: if deletion is interrupted, the next start reinstalls instead of trusting a half-deleted tree.
        val record = File(Paths.state, "workspace.json")
        val recordOk = !record.exists() || record.delete()
        val rootfsOk = HostFiles.deleteTree(Paths.rootfs)
        val runtimeOk = HostFiles.deleteTree(Paths.runtime)
        return when {
            !recordOk -> "无法删除工作区记录，请稍后再试"
            !rootfsOk || !runtimeOk -> "有少量文件没能删除。重启后会重新创建工作区，若仍有问题可在系统设置里清除 HaniyaX 的数据"
            else -> null
        }
    }

    /** Relaunches the main activity in a fresh process. */
    fun restartApp(context: Context) {
        val intent = Intent(context.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法重新打开 HaniyaX", e)
        }
        exitProcess(0)
    }

    private const val TAG = "HxReset"
    private const val STOP_WAIT_MS = 5_000L
}
