package dev.haniya.x.terminal

import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import dev.haniya.x.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/** How the soft keyboard talks to the terminal. */
@Serializable
enum class TerminalInputMode {
    /** Text field without suggestions: Chinese / Japanese / Korean IMEs can compose; only committed text is sent. */
    Text,
    /** Character-by-character (visible password variation) for keyboards that misbehave in text fields. */
    CharByChar,
}

/** Terminal-only preferences that AppSettings does not carry. Persisted in Paths.state/terminal.json. */
@Serializable
data class TerminalPrefsData(
    val inputMode: TerminalInputMode = TerminalInputMode.Text,
    /** Compose bar: press Enter after sending. */
    val enterAfterSend: Boolean = true,
)

object TerminalPrefs {
    private const val TAG = "HaniyaX.TermPrefs"
    private val file get() = File(Paths.state, "terminal.json")

    private val state = MutableStateFlow(load())
    val prefs: StateFlow<TerminalPrefsData> = state.asStateFlow()

    init {
        App.scope.launch(Dispatchers.IO) {
            state.drop(1).collect { v ->
                try {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(AppJson.encodeToString(TerminalPrefsData.serializer(), v))
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法保存终端偏好", e)
                }
            }
        }
    }

    fun update(transform: (TerminalPrefsData) -> TerminalPrefsData) = state.update(transform)

    private fun load(): TerminalPrefsData = try {
        val f = file
        if (f.isFile) AppJson.decodeFromString(TerminalPrefsData.serializer(), f.readText()) else TerminalPrefsData()
    } catch (e: Exception) {
        Log.w(TAG, "无法读取终端偏好，已使用默认值", e)
        TerminalPrefsData()
    }
}
