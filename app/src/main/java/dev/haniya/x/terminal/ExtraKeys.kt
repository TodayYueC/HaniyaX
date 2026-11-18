package dev.haniya.x.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haniya.relief.component.GlassLevel
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.glass
import dev.haniya.relief.component.gradientSurface
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.theme.ReliefTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Visual state of a key: raised (idle) or pressed into the surface (pressed / active toggle). */
enum class KeyVisual { Idle, Active, Locked }

/**
 * The extra keys rows above the keyboard: raised thin keys on the ceramic surface; pressed and active keys are inset.
 * Rows fill the width evenly and scroll horizontally when the keys would get too narrow.
 *
 * [visualOf] reports toggles that are on (sticky CTRL/ALT, open compose bar, search, fullscreen…).
 */
@Composable
fun ExtraKeysBar(
    layout: ExtraKeysLayout,
    visualOf: (ExtraKey) -> KeyVisual,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        for (row in layout.rows) {
            if (row.isNotEmpty()) KeyRow(row, visualOf, onKey)
        }
    }
}

private val KEY_GAP = 5.dp
private val KEY_MIN = 30.dp
private val KEY_SCROLL = 40.dp
private val KEY_HEIGHT = 38.dp

@Composable
private fun KeyRow(keys: List<ExtraKey>, visualOf: (ExtraKey) -> KeyVisual, onKey: (ExtraKey) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = keys.size
        val even = (maxWidth - KEY_GAP * (n - 1)) / n
        if (even >= KEY_MIN) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                keys.forEach { k -> ExtraKeyButton(k, visualOf(k), even, onKey) }
            }
        } else {
            // Too many keys for the width: keep them comfortable and let the row scroll.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            ) {
                keys.forEach { k -> ExtraKeyButton(k, visualOf(k), KEY_SCROLL, onKey) }
            }
        }
    }
}

/** Hand-drawn icon shown for a key instead of its label. */
internal fun keyIcon(k: ExtraKey): String? = k.icon ?: when (k.id) {
    "up" -> "chevU"
    "down" -> "chevD"
    "left" -> "chevL"
    "right" -> "chevR"
    else -> null
}

@Composable
private fun ExtraKeyButton(key: ExtraKey, visual: KeyVisual, width: Dp, onKey: (ExtraKey) -> Unit) {
    val c = ReliefTheme.colors
    val shape = RoundedCornerShape(11.dp)
    var pressed by remember { mutableStateOf(false) }
    val currentKey by rememberUpdatedState(key)
    val currentOnKey by rememberUpdatedState(onKey)
    val inset = pressed || visual != KeyVisual.Idle
    val fg = if (visual != KeyVisual.Idle) c.mid else c.ink
    Box(
        Modifier
            .width(width)
            .height(KEY_HEIGHT)
            .then(if (inset) Modifier.insetSurface(shape, depth = 2.6.dp) else Modifier.glass(shape, GlassLevel.Thin))
            .pointerInput(key.id, key.repeat) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        var repeated = false
                        val repeater = if (currentKey.repeat) launch {
                            delay(REPEAT_DELAY_MS)
                            while (isActive) {
                                repeated = true
                                currentOnKey(currentKey)
                                delay(REPEAT_INTERVAL_MS)
                            }
                        } else null
                        val up = waitForUpOrCancellation()
                        repeater?.cancel()
                        pressed = false
                        if (up != null) {
                            up.consume()
                            if (!repeated) currentOnKey(currentKey)
                        }
                    }
                }
            }
            .semantics {
                contentDescription = key.description?.let { "${key.label}，$it" } ?: key.label
                role = Role.Button
                if (visual != KeyVisual.Idle) stateDescription = if (visual == KeyVisual.Locked) "已锁定" else "已开启"
                onClick { currentOnKey(currentKey); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        val icon = keyIcon(key)
        if (icon != null) {
            HIcon(icon, size = 18.dp, tint = fg)
        } else {
            val symbol = key.action is KeyAction.Symbol
            Text(
                key.label,
                style = if (symbol) ReliefTheme.type.body.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                else ReliefTheme.type.badge.copy(fontSize = if (width < 36.dp) 11.sp else 12.sp),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                translate = false,
            )
        }
        if (visual == KeyVisual.Locked) {
            // Locked modifier: a short glaze bar under the label.
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(14.dp, 3.dp)
                    .clip(RoundedCornerShape(2.dp)).gradientSurface(RoundedCornerShape(2.dp), glow = 2.dp, shadow = false),
            )
        }
    }
}

private const val REPEAT_DELAY_MS = 380L
private const val REPEAT_INTERVAL_MS = 55L
