package dev.haniya.x.terminal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * Extra keys: pure model, catalogue and JSON codec (no Android types, unit-tested).
 *
 * Stored in AppSettings.extraKeys as JSON:
 *   {"version":1,"rows":[["esc","tab","ctrl",…],["-","|",…,{"label":"gs","text":"git status\n"}]]}
 * Each row entry is a catalogue id ("esc", "f5", "ctrl-c", "paste" …), a single symbol character ("=", "/" …) or a
 * macro object {"label": "…", "text": "…"} whose text is written to the terminal as-is (newline = Enter).
 * A bare array of rows is accepted as well. Unknown ids are skipped and reported.
 */

/** Sticky modifiers shown on the extra keys bar. */
enum class Mod { CTRL, ALT, SHIFT }

/** Terminal-screen commands an extra key can trigger. */
enum class TerminalCommand { PASTE, COMPOSE, SEARCH, FULLSCREEN, KEYBOARD }

/** What pressing an extra key does. */
sealed interface KeyAction {
    /** An Android key code, sent through the terminal's key handling (honours sticky modifiers). */
    data class Key(val keyCode: Int) : KeyAction
    /** One character, typed like a keyboard character (honours sticky CTRL / ALT). */
    data class Symbol(val codePoint: Int) : KeyAction
    /** Raw text written to the terminal unchanged (control sequences, macros). Newlines become Enter. */
    data class Text(val text: String) : KeyAction
    data class Sticky(val mod: Mod) : KeyAction
    data class Command(val command: TerminalCommand) : KeyAction
}

/** One key on the bar. [id] is the catalogue id, the symbol itself, or [ExtraKeys.MACRO_ID] for macros. */
data class ExtraKey(
    val id: String,
    val label: String,
    val action: KeyAction,
    /** Hand-drawn icon name shown instead of the label (label stays the accessibility name). */
    val icon: String? = null,
    /** Long press repeats the key. */
    val repeat: Boolean = false,
    /** Short explanation shown in the editor. */
    val description: String? = null,
) {
    val isMacro: Boolean get() = id == ExtraKeys.MACRO_ID
}

data class ExtraKeysLayout(val rows: List<List<ExtraKey>>)

data class CatalogueGroup(val title: String, val keys: List<ExtraKey>)

data class ExtraKeysParseResult(
    val layout: ExtraKeysLayout,
    /** Human-readable (Chinese) notes about entries that were skipped. */
    val problems: List<String>,
    val usedDefault: Boolean,
)

/** Android KeyEvent key codes used by the catalogue (values from android.view.KeyEvent). */
object KeyCodes {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val TAB = 61
    const val ENTER = 66
    const val DEL = 67
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val F1 = 131
}

object ExtraKeys {
    const val MACRO_ID = "macro"
    const val MAX_ROWS = 3
    const val MAX_KEYS_PER_ROW = 16
    const val MAX_MACRO_LABEL = 8
    const val MAX_MACRO_TEXT = 1000

    private fun key(id: String, label: String, code: Int, repeat: Boolean = false, description: String? = null) =
        ExtraKey(id, label, KeyAction.Key(code), repeat = repeat, description = description)

    private fun text(id: String, label: String, text: String, description: String) =
        ExtraKey(id, label, KeyAction.Text(text), description = description)

    private fun command(id: String, label: String, c: TerminalCommand, icon: String? = null, description: String) =
        ExtraKey(id, label, KeyAction.Command(c), icon = icon, description = description)

    private val basics = listOf(
        key("esc", "ESC", KeyCodes.ESCAPE, description = "退出 / 取消"),
        key("tab", "TAB", KeyCodes.TAB, description = "补全"),
        ExtraKey("ctrl", "CTRL", KeyAction.Sticky(Mod.CTRL), description = "单击作用于下一个键，双击锁定"),
        ExtraKey("alt", "ALT", KeyAction.Sticky(Mod.ALT), description = "单击作用于下一个键，双击锁定"),
        ExtraKey("shift", "SHIFT", KeyAction.Sticky(Mod.SHIFT), description = "单击作用于下一个键，双击锁定"),
        key("enter", "回车", KeyCodes.ENTER),
        key("bksp", "退格", KeyCodes.DEL, repeat = true),
        key("del", "DEL", KeyCodes.FORWARD_DEL, repeat = true, description = "向后删除"),
        key("ins", "INS", KeyCodes.INSERT),
    )

    private val navigation = listOf(
        key("up", "↑", KeyCodes.DPAD_UP, repeat = true),
        key("down", "↓", KeyCodes.DPAD_DOWN, repeat = true),
        key("left", "←", KeyCodes.DPAD_LEFT, repeat = true),
        key("right", "→", KeyCodes.DPAD_RIGHT, repeat = true),
        key("home", "HOME", KeyCodes.MOVE_HOME),
        key("end", "END", KeyCodes.MOVE_END),
        key("pgup", "PGUP", KeyCodes.PAGE_UP, repeat = true),
        key("pgdn", "PGDN", KeyCodes.PAGE_DOWN, repeat = true),
    )

    private val combos = listOf(
        text("ctrl-c", "^C", "\u0003", "中断当前命令"),
        text("ctrl-d", "^D", "\u0004", "结束输入 / 退出"),
        text("ctrl-r", "^R", "\u0012", "搜索命令历史"),
        text("ctrl-z", "^Z", "\u001a", "挂起到后台"),
        text("ctrl-l", "^L", "\u000c", "清屏"),
        text("ctrl-a", "^A", "\u0001", "移到行首"),
        text("ctrl-e", "^E", "\u0005", "移到行尾"),
        text("ctrl-u", "^U", "\u0015", "删除光标前的内容"),
        text("ctrl-w", "^W", "\u0017", "删除前一个词"),
        text("shift-tab", "S-TAB", "\u001b[Z", "反向切换（Shift+Tab）"),
    )

    private val functionKeys = (1..12).map { n -> key("f$n", "F$n", KeyCodes.F1 + n - 1) }

    /** Symbols offered by the catalogue (any other single character is accepted from JSON too). */
    val SYMBOLS: List<String> = listOf(
        "-", "|", "~", "/", "\\", "_", ":", ";", "=", "+", "*", "&", "$", "#", "!", "?", "@", "^", "%",
        "<", ">", "'", "\"", "`", "(", ")", "[", "]", "{", "}",
    )

    private val symbols = SYMBOLS.map(::symbolKey)

    private val commands = listOf(
        command("paste", "粘贴", TerminalCommand.PASTE, description = "粘贴剪贴板（长文本先预览）"),
        command("compose", "撰写", TerminalCommand.COMPOSE, description = "在输入栏里写好再发送，适合中文与长文本"),
        command("search", "搜索", TerminalCommand.SEARCH, description = "搜索回滚内容"),
        command("fullscreen", "全屏", TerminalCommand.FULLSCREEN, description = "隐藏标签栏和系统栏"),
        command("keyboard", "键盘", TerminalCommand.KEYBOARD, icon = "keyboard", description = "显示或收起软键盘"),
    )

    val catalogue: List<CatalogueGroup> = listOf(
        CatalogueGroup("常用", basics),
        CatalogueGroup("方向与翻页", navigation),
        CatalogueGroup("组合键", combos),
        CatalogueGroup("功能键", functionKeys),
        CatalogueGroup("符号", symbols),
        CatalogueGroup("操作", commands),
    )

    private val byId: Map<String, ExtraKey> = catalogue.flatMap { it.keys }.associateBy { it.id }

    val DEFAULT_IDS: List<List<String>> = listOf(
        listOf("esc", "tab", "ctrl", "alt", "up", "down", "left", "right", "home", "end"),
        listOf("-", "|", "~", "pgup", "pgdn", "paste", "compose", "search", "fullscreen", "keyboard"),
    )

    val DEFAULT: ExtraKeysLayout = ExtraKeysLayout(DEFAULT_IDS.map { row -> row.map { byId.getValue(it) } })

    fun byId(id: String): ExtraKey? = byId[id]

    fun symbolKey(symbol: String): ExtraKey = ExtraKey(symbol, symbol, KeyAction.Symbol(symbol.codePointAt(0)))

    /** A user macro. [text] is sent as-is (newline = Enter). */
    fun macro(label: String, text: String): ExtraKey =
        ExtraKey(MACRO_ID, label.trim().take(MAX_MACRO_LABEL), KeyAction.Text(text.take(MAX_MACRO_TEXT)), description = macroPreview(text))

    private fun macroPreview(text: String): String =
        text.replace("\r\n", "\n").replace("\n", "⏎").replace("\u001b", "⎋").take(40)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Resolves one stored entry; null if it cannot be used. */
    fun resolve(entry: String): ExtraKey? {
        byId[entry]?.let { return it }
        if (entry.isNotEmpty() && entry.codePointCount(0, entry.length) == 1 && !entry[0].isISOControl() && !entry[0].isWhitespace()) {
            return symbolKey(entry)
        }
        return null
    }

    /** Parses [text] (from settings). null / blank → the default layout. Never throws. */
    fun parse(text: String?): ExtraKeysParseResult {
        if (text.isNullOrBlank()) return ExtraKeysParseResult(DEFAULT, emptyList(), usedDefault = true)
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ExtraKeysParseResult(DEFAULT, listOf("扩展键配置不是有效的 JSON，已使用默认布局"), usedDefault = true)
        }
        val rowsElement: JsonElement? = when (root) {
            is JsonArray -> root
            is JsonObject -> root["rows"]
            else -> null
        }
        if (rowsElement !is JsonArray) {
            return ExtraKeysParseResult(DEFAULT, listOf("扩展键配置缺少 rows，已使用默认布局"), usedDefault = true)
        }
        val problems = mutableListOf<String>()
        val rows = mutableListOf<List<ExtraKey>>()
        for ((rowIndex, rowElement) in rowsElement.withIndex()) {
            if (rows.size >= MAX_ROWS) {
                problems += "最多 $MAX_ROWS 行，多余的行已忽略"
                break
            }
            if (rowElement !is JsonArray) {
                problems += "第 ${rowIndex + 1} 行不是列表，已跳过"
                continue
            }
            val keys = mutableListOf<ExtraKey>()
            for (entry in rowElement) {
                if (keys.size >= MAX_KEYS_PER_ROW) {
                    problems += "第 ${rowIndex + 1} 行最多 $MAX_KEYS_PER_ROW 个键，多余的已忽略"
                    break
                }
                val k = resolveElement(entry)
                if (k == null) problems += "无法识别的键：${describe(entry)}" else keys += k
            }
            if (keys.isNotEmpty()) rows += keys
        }
        if (rows.isEmpty()) {
            problems += "扩展键配置里没有可用的键，已使用默认布局"
            return ExtraKeysParseResult(DEFAULT, problems, usedDefault = true)
        }
        return ExtraKeysParseResult(ExtraKeysLayout(rows), problems, usedDefault = false)
    }

    private fun resolveElement(e: JsonElement): ExtraKey? = when (e) {
        is JsonPrimitive -> if (e.isString) resolve(e.content) else null
        is JsonObject -> {
            val text = (e["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val label = (e["label"] as? JsonPrimitive)?.contentOrNull
            val id = (e["id"] as? JsonPrimitive)?.contentOrNull
            when {
                text != null && (id == null || id == MACRO_ID) -> {
                    val l = label?.trim().orEmpty().ifEmpty { text.trim().take(MAX_MACRO_LABEL) }
                    if (text.isEmpty() || l.isEmpty()) null else macro(l, text)
                }
                id != null -> resolve(id)
                else -> null
            }
        }
        else -> null
    }

    private fun describe(e: JsonElement): String = when (e) {
        is JsonPrimitive -> e.content.take(20)
        JsonNull -> "null"
        else -> e.toString().take(20)
    }

    /** Serialises a layout back to the stored JSON form. */
    fun encode(layout: ExtraKeysLayout): String {
        val obj = buildJsonObject {
            put("version", 1)
            put("rows", buildJsonArray {
                for (row in layout.rows) add(buildJsonArray {
                    for (k in row) add(
                        if (k.isMacro) buildJsonObject {
                            put("label", k.label)
                            put("text", (k.action as KeyAction.Text).text)
                        } else JsonPrimitive(k.id),
                    )
                })
            })
        }
        return obj.toString()
    }

    /**
     * Turns what the user typed in the macro editor into the text to send: `\n` (or a real line break) = Enter,
     * `\t` = Tab, `\e` = Escape, `\\` = backslash. Other backslashes are kept.
     */
    fun unescapeMacro(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'e' -> { sb.append('\u001b'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** Text a macro / raw key writes to the pty: line breaks become carriage returns (Enter). */
    fun bytesFor(text: String): String = text.replace("\r\n", "\r").replace('\n', '\r')
}

/** Sticky modifier state: single tap = next key only, double tap = locked, tap again = off. */
enum class StickyState { Off, Once, Locked }

object StickyLogic {
    const val DOUBLE_TAP_MS = 350L

    fun onTap(current: StickyState, sinceLastTapMs: Long): StickyState = when (current) {
        StickyState.Off -> StickyState.Once
        StickyState.Once -> if (sinceLastTapMs in 0 until DOUBLE_TAP_MS) StickyState.Locked else StickyState.Off
        StickyState.Locked -> StickyState.Off
    }

    /** State after the modifier has been applied to one key. */
    fun afterUse(current: StickyState): StickyState = if (current == StickyState.Once) StickyState.Off else current

    fun isActive(s: StickyState) = s != StickyState.Off
}

/** Editing operations for the layout editor (pure). */
object ExtraKeysEdit {
    fun move(layout: ExtraKeysLayout, row: Int, index: Int, delta: Int): ExtraKeysLayout {
        val r = layout.rows.getOrNull(row) ?: return layout
        val target = index + delta
        if (index !in r.indices || target !in r.indices) return layout
        val m = r.toMutableList()
        val k = m.removeAt(index)
        m.add(target, k)
        return layout.copy(rows = layout.rows.mapIndexed { i, old -> if (i == row) m else old })
    }

    fun remove(layout: ExtraKeysLayout, row: Int, index: Int): ExtraKeysLayout {
        val r = layout.rows.getOrNull(row) ?: return layout
        if (index !in r.indices) return layout
        val rows = layout.rows.mapIndexed { i, old -> if (i == row) old.filterIndexed { j, _ -> j != index } else old }
        return layout.copy(rows = rows)
    }

    /** Appends [key] to [row]; returns null when the row is full. */
    fun add(layout: ExtraKeysLayout, row: Int, key: ExtraKey): ExtraKeysLayout? {
        val r = layout.rows.getOrNull(row) ?: return null
        if (r.size >= ExtraKeys.MAX_KEYS_PER_ROW) return null
        return layout.copy(rows = layout.rows.mapIndexed { i, old -> if (i == row) old + key else old })
    }

    /** Moves a key to the end of another row. */
    fun moveToRow(layout: ExtraKeysLayout, row: Int, index: Int, toRow: Int): ExtraKeysLayout {
        if (row == toRow) return layout
        val key = layout.rows.getOrNull(row)?.getOrNull(index) ?: return layout
        val dest = layout.rows.getOrNull(toRow) ?: return layout
        if (dest.size >= ExtraKeys.MAX_KEYS_PER_ROW) return layout
        return add(remove(layout, row, index), toRow, key) ?: layout
    }

    fun addRow(layout: ExtraKeysLayout): ExtraKeysLayout =
        if (layout.rows.size >= ExtraKeys.MAX_ROWS) layout else layout.copy(rows = layout.rows + listOf(emptyList()))

    fun removeRow(layout: ExtraKeysLayout, row: Int): ExtraKeysLayout =
        if (layout.rows.size <= 1 || row !in layout.rows.indices) layout
        else layout.copy(rows = layout.rows.filterIndexed { i, _ -> i != row })

    /** Layout ready to store: empty rows dropped; never empty. */
    fun normalized(layout: ExtraKeysLayout): ExtraKeysLayout {
        val rows = layout.rows.filter { it.isNotEmpty() }
        return if (rows.isEmpty()) ExtraKeys.DEFAULT else ExtraKeysLayout(rows)
    }
}
