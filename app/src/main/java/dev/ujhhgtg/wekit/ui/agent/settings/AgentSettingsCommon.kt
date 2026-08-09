package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.miuixAppBarBlur
import dev.ujhhgtg.wekit.ui.content.miuixAppBarColor
import dev.ujhhgtg.wekit.ui.content.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Bottom padding so scrollable content clears the system nav bar comfortably. */
val AGENT_CONTENT_BOTTOM_INSET = 32.dp

/**
 * Standard scaffold for every WeAgent settings sub-screen: collapsing blurred [TopAppBar] with a
 * back button + a scroll-through-blur [LazyColumn], mirroring
 * [dev.ujhhgtg.wekit.activity.settings.MiuixListScaffold] but with a navigation icon.
 */
@Composable
fun AgentSettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val barBackdrop = rememberMiuixBlurBackdrop()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.miuixAppBarBlur(barBackdrop),
                color = barBackdrop.miuixAppBarColor(),
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                MiuixIcons.Back,
                                contentDescription = stringResource(R.string.accessibility_back),
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    }
                },
            )
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .then(barBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
            content = content,
        )
    }
}

/** Empty-state placeholder row for a list with no entries yet. */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.padding(vertical = 24.dp)) {
        top.yukonga.miuix.kmp.basic.Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
    }
}
