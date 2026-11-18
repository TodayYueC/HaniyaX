package dev.haniya.x.plugins

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.haniya.relief.component.GlassSheet
import kotlinx.coroutines.delay

/**
 * A [GlassSheet] that can be opened from anywhere (e.g. from inside a home card in a scrolling list):
 * it is hosted in its own full-screen, undimmed dialog window so it overlays the whole screen,
 * keeps the Relief theme (composition locals flow into dialogs) and animates in and out.
 */
@Composable
internal fun OverlaySheet(
    open: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) { mounted = true; shown = true }
        else if (mounted) { shown = false; delay(360); mounted = false }
    }
    if (!mounted) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setDimAmount(0f)
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setWindowAnimations(0)
                @Suppress("DEPRECATION")
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        // Start hidden inside the new window so GlassSheet plays its enter animation.
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(shown) { visible = shown }
        Box(Modifier.fillMaxSize()) {
            GlassSheet(visible = visible, onDismiss = onDismiss, title = title, content = content)
        }
    }
}
