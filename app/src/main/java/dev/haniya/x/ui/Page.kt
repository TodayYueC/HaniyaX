package dev.haniya.x.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haniya.relief.component.IconCircleButton
import dev.haniya.relief.component.Text
import dev.haniya.relief.theme.ReliefTheme

/** Spacing tokens from the Haniya Relief spec (references/tokens.md). */
object Space {
    val gutter = 20.dp
    val cardGap = 18.dp
    val rowGap = 20.dp
}

/**
 * Standard page header: round back button, page title (+caption), trailing actions.
 * Respects the status bar inset. Shared by every screen except Home and Terminal.
 */
@Composable
fun PageHeader(
    title: String,
    caption: String? = null,
    onBack: (() -> Unit)? = { Nav.back() },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = ReliefTheme.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Space.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) IconCircleButton("chevL", contentDescription = "返回", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = ReliefTheme.type.pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = ReliefTheme.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/**
 * Scrolling page: header + vertically scrolling column with 20dp gutters. Leaves room for relief
 * shadows (never clip raised children tighter than the gutter). Handles IME + nav bar insets.
 */
@Composable
fun Page(
    title: String,
    caption: String? = null,
    onBack: (() -> Unit)? = { Nav.back() },
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Space.gutter),
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(title, caption, onBack, actions)
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .imePadding()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(Space.cardGap),
            ) {
                Spacer(Modifier.height(4.dp))
                content()
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(28.dp))
            }
        }
        overlay()
    }
}
