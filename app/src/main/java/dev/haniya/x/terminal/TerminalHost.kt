package dev.haniya.x.terminal

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.WcWidth
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.haniya.x.App
import kotlin.math.roundToInt

/**
 * Owns the single [TerminalView] of the terminal screen: sticky modifiers, key sending, IME, font size, paste,
 * scrollback access and the [TerminalViewClient] callbacks. All methods are called on the main thread.
 */
@Stable
class TerminalController(private val context: Context) {
    var view: TerminalView? = null
        private set

    var ctrl by mutableStateOf(StickyState.Off)
        private set
    var alt by mutableStateOf(StickyState.Off)
        private set
    var shift by mutableStateOf(StickyState.Off)
        private set
    private val lastTap = LongArray(Mod.entries.size)

    /** Long-press text selection is active. */
    var selecting by mutableStateOf(false)
        private set

    /** Font size currently shown (sp). Pinch changes it and persists it to settings. */
    var fontSp by mutableIntStateOf(App.settings.settings.value.terminalFontSp.coerceIn(FONT_MIN, FONT_MAX))
        private set
    private var appliedSp = -1
    private var appliedBackground = 0

    /** Tap on the terminal text (not on a TUI with mouse reporting). */
    var onTerminalTap: () -> Unit = { showIme() }

    val session: TerminalSession? get() = view?.currentSession
    val emulator: TerminalEmulator? get() = view?.mEmulator ?: session?.emulator

    /** The attached session has a live process. */
    val running: Boolean get() = session?.isRunning == true

    /** Whether the program in the terminal enabled bracketed paste (DECSET 2004). */
    val bracketedPaste: Boolean get() = emulator?.isBracketedPasteMode == true

    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    // ---- view lifecycle ----

    fun attachView(v: TerminalView) {
        view = v
        appliedSp = -1
        appliedBackground = 0
    }

    fun detachView(v: TerminalView) {
        if (view === v) view = null
    }

    /** Brings the view in line with the composition: session, font size, input mode, background colour. */
    fun bind(v: TerminalView, session: TerminalSession?, fontSp: Int, inputMode: TerminalInputMode, background: Int) {
        val sp = fontSp.coerceIn(FONT_MIN, FONT_MAX)
        this.fontSp = sp
        if (appliedSp != sp) {
            v.setTextSize(px(sp))
            appliedSp = sp
        }
        v.setInputMode(if (inputMode == TerminalInputMode.CharByChar) TerminalView.INPUT_MODE_VISIBLE_PASSWORD else TerminalView.INPUT_MODE_TEXT)
        if (appliedBackground != background) {
            v.setBackgroundColor(background)
            appliedBackground = background
            v.invalidate()
        }
        if (v.currentSession !== session) {
            if (v.isSelectingText) v.stopTextSelectionMode()
            v.setHoldScrollPosition(false)
            // A highlight belongs to the previous session's rows; on another session it can cover the whole screen.
            v.clearSearchHighlight()
            v.attachSession(session)
            if (session != null && v.mEmulator != null) v.onScreenUpdated()
            v.invalidate()
        }
    }

    /** Redraw after the session's screen changed. */
    fun screenUpdated() {
        val v = view ?: return
        if (v.mEmulator != null) v.onScreenUpdated() else v.invalidate()
    }

    // ---- modifiers ----

    fun state(mod: Mod): StickyState = when (mod) {
        Mod.CTRL -> ctrl
        Mod.ALT -> alt
        Mod.SHIFT -> shift
    }

    private fun set(mod: Mod, s: StickyState) {
        when (mod) {
            Mod.CTRL -> ctrl = s
            Mod.ALT -> alt = s
            Mod.SHIFT -> shift = s
        }
    }

    fun tapModifier(mod: Mod) {
        val now = SystemClock.uptimeMillis()
        val since = now - lastTap[mod.ordinal]
        lastTap[mod.ordinal] = now
        set(mod, StickyLogic.onTap(state(mod), since))
    }

    /** Reads a modifier for one key and releases it if it was a single-use press. */
    private fun read(mod: Mod): Boolean {
        val s = state(mod)
        if (s == StickyState.Once) set(mod, StickyState.Off)
        return s != StickyState.Off
    }

    private fun releaseOnce() {
        for (m in Mod.entries) set(m, StickyLogic.afterUse(state(m)))
    }

    // ---- input ----

    /** Performs a non-command extra key. */
    fun press(key: ExtraKey) {
        val v = view ?: return
        when (val a = key.action) {
            is KeyAction.Sticky -> {
                tapModifier(a.mod)
                return
            }
            is KeyAction.Command -> return
            else -> Unit
        }
        if (v.currentSession == null) return
        // Pending IME composition (e.g. pinyin letters) must not mix into the key sequence.
        v.cancelComposition()
        if (v.isSelectingText) v.stopTextSelectionMode()
        when (val a = key.action) {
            is KeyAction.Key -> sendKeyCode(v, a.keyCode)
            is KeyAction.Symbol -> v.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, a.codePoint, false, false)
            is KeyAction.Text -> v.currentSession?.write(ExtraKeys.bytesFor(a.text))
            else -> Unit
        }
        releaseOnce()
    }

    private fun sendKeyCode(v: TerminalView, code: Int) {
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
        v.onKeyDown(code, down)
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
        v.onKeyUp(code, up)
    }

    /** Writes text (already prepared for the pty) to the session. Returns false when nothing is running. */
    fun write(text: String): Boolean {
        val s = session ?: return false
        if (!s.isRunning) return false
        if (text.isEmpty()) return true
        view?.cancelComposition()
        s.write(text)
        return true
    }

    /** Pastes like a terminal: ESC/C1 stripped, newlines as CR, wrapped in bracketed-paste markers when enabled. */
    fun paste(text: String): Boolean {
        if (!running) return false
        if (text.isEmpty()) return true
        val v = view
        v?.cancelComposition()
        if (v?.isSelectingText == true) v.stopTextSelectionMode()
        val e = emulator
        if (e != null) e.paste(text) else session?.write(PasteText.sanitize(text))
        return true
    }

    /** Plain text currently on the clipboard, or null. */
    fun clipboardText(): String? = try {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "clipboard read failed", e)
        null
    }

    fun showIme() {
        val v = view ?: return
        v.requestFocus()
        v.post { imm?.showSoftInput(v, 0) }
    }

    fun hideIme() {
        val v = view ?: return
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Gives keyboard focus back to the terminal (after the compose bar closed). */
    fun focusTerminal(showKeyboard: Boolean) {
        val v = view ?: return
        v.requestFocus()
        if (showKeyboard) v.post { imm?.showSoftInput(v, 0) }
    }

    fun haptic() {
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Scroll back to the live bottom of the terminal. */
    fun scrollToBottom() {
        val v = view ?: return
        v.setTopRow(0)
        v.invalidate()
    }

    fun setFont(sp: Int) {
        val clamped = sp.coerceIn(FONT_MIN, FONT_MAX)
        if (clamped == fontSp && clamped == appliedSp) return
        fontSp = clamped
        view?.let {
            it.setTextSize(px(clamped))
            appliedSp = clamped
        }
        App.settings.update { if (it.terminalFontSp == clamped) it else it.copy(terminalFontSp = clamped) }
    }

    private fun px(sp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).roundToInt()

    // ---- scrollback search ----

    /**
     * Position marker for rows captured now: rows the screen scrolled since the session was attached, including
     * scrolling the view has not processed yet. See [ScrollbackMatcher.shift].
     */
    fun scrollMark(): Long = (view?.scrolledRowsTotal ?: 0L) + (emulator?.scrollCounter ?: 0)

    /** Every row of the transcript and screen as [ScreenRow]s (external row coordinates), oldest first. */
    fun snapshotRows(): List<ScreenRow> {
        val e = emulator ?: return emptyList()
        val screen = e.screen
        val first = -screen.activeTranscriptRows
        val out = ArrayList<ScreenRow>(e.mRows - first)
        for (r in first until e.mRows) out += rowAt(e, r)
        return out
    }

    private fun rowAt(e: TerminalEmulator, r: Int): ScreenRow {
        val screen = e.screen
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(r))
        val used = line.spaceUsed.coerceAtMost(line.mText.size)
        val chars = line.mText
        val columns = IntArray(used)
        val widths = IntArray(used)
        var col = 0
        var lastStart = 0
        var i = 0
        while (i < used) {
            val ch = chars[i]
            if (Character.isHighSurrogate(ch) && i + 1 < used && Character.isLowSurrogate(chars[i + 1])) {
                val w = WcWidth.width(Character.toCodePoint(ch, chars[i + 1]))
                if (w > 0) {
                    columns[i] = col; widths[i] = w; lastStart = col; col += w
                } else {
                    columns[i] = lastStart; widths[i] = 0
                }
                columns[i + 1] = columns[i]; widths[i + 1] = 0
                i += 2
                continue
            }
            val w = WcWidth.width(ch.code)
            if (w > 0) {
                columns[i] = col; widths[i] = w; lastStart = col; col += w
            } else {
                columns[i] = lastStart; widths[i] = 0
            }
            i++
        }
        val wraps = try { screen.getLineWrap(r) } catch (_: IllegalArgumentException) { false }
        return ScreenRow(r, String(chars, 0, used), columns, widths, wraps)
    }

    /**
     * Scrolls so [m] (captured at [mark]) is visible and highlights it. Returns false when the match is no longer
     * where it was (scrolled out of the transcript, or the screen was reflowed) - the caller should search again.
     */
    fun showMatch(m: SearchMatch, mark: Long, query: String): Boolean {
        val v = view ?: return false
        val e = emulator ?: return false
        val mm = ScrollbackMatcher.shift(m, mark, scrollMark())
        val transcript = e.screen.activeTranscriptRows
        if (mm.startRow < -transcript || mm.endRow >= e.mRows || mm.startRow > mm.endRow) return false
        val text = StringBuilder()
        for (r in mm.startRow..mm.endRow) text.append(rowAt(e, r).text)
        if (!text.contains(query, ignoreCase = true)) return false
        v.setHoldScrollPosition(true)
        v.setTopRow(ScrollbackMatcher.topRowFor(mm, v.topRow, e.mRows, transcript))
        v.setSearchHighlight(mm.startRow, mm.startColumn, mm.endRow, mm.endColumn)
        v.invalidate()
        return true
    }

    fun beginSearch() {
        view?.setHoldScrollPosition(true)
    }

    fun endSearch() {
        val v = view ?: return
        v.clearSearchHighlight()
        v.setHoldScrollPosition(false)
        scrollToBottom()
    }

    fun clearHighlight() {
        view?.clearSearchHighlight()
    }

    // ---- TerminalViewClient ----

    val client: TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                setFont(fontSp + if (scale > 1f) 1 else -1)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            onTerminalTap()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) {
            selecting = copyMode
        }

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean = read(Mod.CTRL)
        override fun readAltKey(): Boolean = read(Mod.ALT)
        override fun readShiftKey(): Boolean = read(Mod.SHIFT)
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

        override fun onEmulatorSet() {
            view?.invalidate()
        }

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    companion object {
        private const val TAG = "HaniyaX.Terminal"
        const val FONT_MIN = 9
        const val FONT_MAX = 24
    }
}

/** The Termux [TerminalView] inside Compose. */
@Composable
fun TerminalHost(
    controller: TerminalController,
    session: TerminalSession?,
    fontSp: Int,
    inputMode: TerminalInputMode,
    background: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        // Termux's renderer fills its canvas with drawColor(…, SRC); without clipping it would paint over
        // the Compose UI around the view (tab bar, extra keys).
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            TerminalView(ctx, null).also { v ->
                v.setTerminalViewClient(controller.client)
                v.isFocusable = true
                v.isFocusableInTouchMode = true
                v.setTextSize(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat(), ctx.resources.displayMetrics).roundToInt(),
                )
                v.setTypeface(TerminalFonts.typeface(ctx))
                v.setBackgroundColor(background)
                v.isVerticalScrollBarEnabled = true
                controller.attachView(v)
                TerminalTheme.register(v)
            }
        },
        update = { v -> controller.bind(v, session, fontSp, inputMode, background) },
        onRelease = { v ->
            controller.detachView(v)
            TerminalTheme.unregister(v)
        },
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
