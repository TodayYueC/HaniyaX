package dev.haniya.x.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haniya.relief.component.ButtonSize
import dev.haniya.relief.component.GlassButton
import dev.haniya.relief.component.GlassLevel
import dev.haniya.relief.component.GlassSheet
import dev.haniya.relief.component.IconTile
import dev.haniya.relief.component.PrimaryButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.component.glass
import dev.haniya.relief.component.insetSurface
import dev.haniya.relief.component.pressable
import dev.haniya.relief.icon.HIcon
import dev.haniya.relief.icon.ReliefIcons
import dev.haniya.relief.theme.ReliefTheme
import kotlinx.coroutines.delay

/** Small calm label above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ReliefTheme.type.label, color = ReliefTheme.colors.ink2, modifier = modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Monospace text style for paths, commands and logs. */
val monoStyle: TextStyle
    @Composable get() = ReliefTheme.type.body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

/** An icon name that exists in the hand-drawn set, else [fallback]. */
fun iconOr(name: String?, fallback: String): String = if (name != null && ReliefIcons.has(name)) name else fallback

/**
 * Round icon button that can show an on-state: raised when off, pressed-in with a gradient icon when
 * [selected]. Dimmed and inert when not [enabled].
 */
@Composable
fun RoundToggle(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    val c = ReliefTheme.colors
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (selected) Modifier.insetSurface(CircleShape, depth = 2.5.dp) else Modifier.glass(CircleShape, GlassLevel.Thin, shadow = enabled))
            .pressable(enabled = enabled, onClick = onClick, onClickLabel = contentDescription)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) HIcon(icon, size = size * 0.46f, brush = c.brush) else HIcon(icon, size = size * 0.46f, tint = c.ink)
    }
}

/** List row for user content (names, paths): not run through UI translation. */
@Composable
fun ItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    titleColor: Color? = null,
    subtitleColor: Color? = null,
    translate: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = ReliefTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.pressable(pressedScale = 1f, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.bodyMedium, color = titleColor ?: c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = translate)
            if (subtitle != null) Text(
                subtitle, style = ReliefTheme.type.caption, color = subtitleColor ?: c.ink2,
                maxLines = 2, overflow = TextOverflow.Ellipsis, translate = translate,
            )
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

/** Action row in sheets: ceramic icon tile + translated label + optional caption. */
@Composable
fun ActionRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ItemRow(
        title = title, subtitle = subtitle, modifier = modifier, titleColor = titleColor, translate = true, onClick = onClick,
        leading = { IconTile(icon, size = 34.dp, filled = false) },
        trailing = trailing ?: { HIcon("chevR", size = 18.dp, tint = ReliefTheme.colors.ink2.copy(alpha = 0.7f)) },
    )
}

/** Label/value row for detail cards. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = ReliefTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = ReliefTheme.type.body, color = c.ink2, modifier = Modifier.width(80.dp), maxLines = 2)
        Text(value, style = if (mono) monoStyle else ReliefTheme.type.body, color = c.ink, modifier = Modifier.weight(1f), translate = false)
        trailing?.invoke()
    }
}

/** Calm, actionable empty state inside a module card. */
@Composable
fun ModuleEmpty(title: String, caption: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = ReliefTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = ReliefTheme.type.h3, color = c.ink)
        Text(caption, style = ReliefTheme.type.caption, color = c.ink2)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            GlassButton(action, onAction, size = ButtonSize.Small)
        }
    }
}

/** Confirmation sheet. [danger] = the action is irreversible (danger-coloured label). */
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val c = ReliefTheme.colors
    GlassSheet(visible = visible, onDismiss = onDismiss, title = title) {
        Text(message, style = ReliefTheme.type.body, color = c.ink2)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("取消", onDismiss, Modifier.weight(1f))
            if (danger) GlassButton(confirmText, onConfirm, Modifier.weight(1f), contentColor = c.danger)
            else PrimaryButton(confirmText, onConfirm, Modifier.weight(1f))
        }
    }
}

/** Current wall-clock time, refreshed every [intervalMs] while composed. */
@Composable
fun rememberNow(intervalMs: Long): Long {
    val now by produceState(System.currentTimeMillis(), intervalMs) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Detects a long press anywhere inside the element before its children see the gesture (Initial pass),
 * so rows with their own click handlers still enter edit mode. The rest of a long-press gesture is
 * consumed, which cancels the child's click.
 */
fun Modifier.longPressCapture(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var done = false
            while (!done) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id }
                done = ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop
            }
            true
        }
        if (ended == null) {
            onLongPress()
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
            } while (ev.changes.any { it.pressed })
        }
    }
}

fun copyToClipboard(context: Context, text: String, label: String = "HaniyaX") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens [url] in another app; false when nothing can handle it. */
fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
