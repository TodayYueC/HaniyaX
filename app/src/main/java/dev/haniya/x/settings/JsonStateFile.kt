package dev.haniya.x.settings

import android.util.Log
import dev.haniya.x.App
import dev.haniya.x.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * A small JSON state file under Paths.state: synchronous [load] (used at construction time),
 * atomic [write], and [autosave] that persists every new value of a StateFlow on Dispatchers.IO.
 */
class JsonStateFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val fallback: () -> T,
) {
    fun load(): T {
        if (!file.isFile) return fallback()
        return try {
            AppJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "无法读取 ${file.name}，已改用默认值", e)
            // Keep the unreadable copy for inspection instead of silently overwriting it.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true) }
            fallback()
        }
    }

    fun write(value: T) {
        HostFiles.writeAtomic(file, AppJson.encodeToString(serializer, value))
    }

    /** Persists each value of [flow] that differs from [initial] (StateFlow conflates bursts). */
    fun autosave(flow: StateFlow<T>, initial: T) {
        App.scope.launch(Dispatchers.IO) {
            var last = initial
            flow.collect { v ->
                if (v != last) {
                    try {
                        write(v)
                        last = v
                    } catch (e: Exception) {
                        Log.w(TAG, "无法保存 ${file.name}", e)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "HxState"
    }
}
