package dev.haniya.x.terminal

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import dev.haniya.relief.theme.ReliefPalette
import dev.haniya.relief.theme.ReliefPalettes
import dev.haniya.x.App
import dev.haniya.x.runtime.Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale
import java.util.Properties
import java.util.WeakHashMap

/**
 * Terminal colours follow the active Relief palette. [apply] writes the palette into Termux's global colour scheme
 * (used by every new emulator), resets the colours of live emulators, sets the renderer's selection / search colours
 * and redraws registered views. The object also follows palette changes on its own (settings + system dark mode),
 * so sessions started while the terminal screen is not shown get the right colours and `COLORFGBG`.
 */
object TerminalTheme {
    private const val TAG = "HaniyaX.TermTheme"
    private val main = Handler(Looper.getMainLooper())
    private val views: MutableSet<TerminalView> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    @Volatile
    var current: TerminalPalette = initialPalette()
        private set

    init {
        writeScheme(current)
        follow()
    }

    /** Applies [p] to the colour scheme, all live sessions and all registered views. Call on the main thread. */
    fun apply(p: TerminalPalette) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { apply(p) }
            return
        }
        val changed = p != current
        current = p
        writeScheme(p)
        if (!changed) {
            invalidateViews()
            return
        }
        // Reset each live emulator to the new defaults (this also drops colours a program set with OSC 4/10/11).
        runCatching {
            for (t in Runtime.tasks.tasks.value) Runtime.tasks.session(t.id)?.emulator?.mColors?.reset()
        }.onFailure { Log.w(TAG, "reset session colours", it) }
        synchronized(views) { views.toList() }.forEach { v -> v.mEmulator?.mColors?.reset() }
        invalidateViews()
    }

    /** Environment for new pty tasks: lets programs that read `COLORFGBG` pick light or dark colours. */
    fun env(): Map<String, String> = mapOf("COLORFGBG" to current.colorFgBg)

    fun register(view: TerminalView) {
        views += view
    }

    fun unregister(view: TerminalView) {
        views -= view
    }

    private fun invalidateViews() {
        synchronized(views) { views.toList() }.forEach { it.invalidate() }
    }

    private fun writeScheme(p: TerminalPalette) {
        val props = Properties()
        props["foreground"] = hex(p.foreground)
        props["background"] = hex(p.background)
        props["cursor"] = hex(p.cursor)
        p.ansi.forEachIndexed { i, c -> props["color$i"] = hex(c) }
        try {
            TerminalColors.COLOR_SCHEME.updateWith(props)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "invalid terminal colours", e)
        }
        TerminalRenderer.sSelectionColor = p.selection
        TerminalRenderer.sSearchHighlightColor = p.searchHighlight
        TerminalRenderer.sEmptyBackgroundColor = p.background
    }

    private fun hex(c: Int) = String.format(Locale.US, "#%06x", c and 0xFFFFFF)

    /** Same rule as ui/HaniyaTheme.activePalette(): user choice, else system light → Platinum, dark → Obsidian. */
    private fun resolvePalette(config: Configuration? = null): ReliefPalette {
        val chosen = runCatching { App.settings.settings.value.palette }.getOrNull()
        if (chosen != null) return chosen
        val night = runCatching {
            val c = config ?: App.context.resources.configuration
            (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)
        return if (night) ReliefPalette.OBSIDIAN else ReliefPalette.PLATINUM
    }

    private fun initialPalette(): TerminalPalette {
        val p = resolvePalette()
        return TerminalPalette.from(ReliefPalettes.of(p), p)
    }

    private fun refresh(config: Configuration? = null) {
        val p = resolvePalette(config)
        if (p != current.palette) apply(TerminalPalette.from(ReliefPalettes.of(p), p))
    }

    private fun follow() {
        runCatching {
            App.scope.launch(Dispatchers.Main) {
                App.settings.settings.map { it.palette }.distinctUntilChanged().collect { refresh() }
            }
        }.onFailure { Log.w(TAG, "cannot follow settings", it) }
        runCatching {
            App.context.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    main.post { refresh(newConfig) }
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {}
            })
        }.onFailure { Log.w(TAG, "cannot follow configuration", it) }
    }
}
