package dev.haniya.x.settings

/**
 * Android 12+ kills "phantom processes" (children of app processes) in the background without warning.
 * Which switch turns that off depends on the Android version (pure, unit-tested).
 */
object BackgroundGuide {
    /** Android 12L / 13 / 14+: disables the phantom-process monitor entirely. */
    const val CMD_FEATURE_FLAG = "adb shell settings put global settings_enable_monitor_phantom_procs false"
    /** Android 12 (API 31): raises the phantom-process cap so the monitor never triggers. */
    const val CMD_DEVICE_CONFIG = "adb shell device_config put activity_manager max_phantom_processes 2147483647"

    data class Guide(
        /** The system limits child processes at all. */
        val limited: Boolean,
        /** Developer options contain the 停用子进程限制 switch (Android 14+). */
        val devOptionsSwitch: Boolean,
        /** adb commands that lift the limit, run from a computer. */
        val commands: List<String>,
        /** One calm sentence describing what to do. */
        val advice: String,
    )

    fun forSdk(sdk: Int): Guide = when {
        sdk >= 34 -> Guide(
            limited = true, devOptionsSwitch = true, commands = listOf(CMD_FEATURE_FLAG),
            advice = "在开发者选项中打开「停用子进程限制」。也可以用电脑执行下面的命令。",
        )
        sdk >= 32 -> Guide(
            limited = true, devOptionsSwitch = false, commands = listOf(CMD_FEATURE_FLAG),
            advice = "连接电脑并开启 USB 调试后，执行下面的命令一次即可，重启手机后仍然有效。",
        )
        sdk == 31 -> Guide(
            limited = true, devOptionsSwitch = false, commands = listOf(CMD_DEVICE_CONFIG),
            advice = "连接电脑并开启 USB 调试后，执行下面的命令。部分系统重启后会恢复，需要再执行一次。",
        )
        else -> Guide(limited = false, devOptionsSwitch = false, commands = emptyList(), advice = "当前系统没有子进程限制，无需处理。")
    }

    fun androidName(sdk: Int): String = when (sdk) {
        26 -> "Android 8.0"
        27 -> "Android 8.1"
        28 -> "Android 9"
        29 -> "Android 10"
        30 -> "Android 11"
        31 -> "Android 12"
        32 -> "Android 12L"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        36 -> "Android 16"
        else -> "Android（API $sdk）"
    }
}

/** Typed confirmation for 重置工作区. */
object ResetConfirm {
    const val WORD = "重置"
    fun matches(input: String): Boolean = input.trim() == WORD
}

/** Stepper increments for the paste-preview thresholds (pure, unit-tested). */
object PasteSteps {
    /** 100-character steps up to 1000, then 500. Clamped to the SettingsStore range. */
    fun chars(value: Int, up: Boolean): Int {
        val next = if (up) {
            if (value < 1000) value + 100 else value + 500
        } else {
            if (value <= 1000) value - 100 else value - 500
        }
        return next.coerceIn(SettingsStore.PASTE_CHARS_MIN, SettingsStore.PASTE_CHARS_MAX)
    }

    fun lines(value: Int, up: Boolean): Int =
        (if (up) value + 1 else value - 1).coerceIn(SettingsStore.PASTE_LINES_MIN, SettingsStore.PASTE_LINES_MAX)
}
