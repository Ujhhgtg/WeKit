@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton

/** Bottom padding so scrollable content clears the system nav bar comfortably. */
val AGENT_CONTENT_BOTTOM_INSET = 32.dp

/**
 * Standard scaffold for every WeAgent settings sub-screen: collapsing blurred
 * [LargeFlexibleTopAppBar] with a back button + a scroll-through-blur [LazyColumn], mirroring
 * [dev.ujhhgtg.wekit.activity.settings.M3ListScaffold] but with a navigation icon.
 */
@Composable
fun AgentSettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val barBackdrop = rememberMaterial3BlurBackdrop()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.m3AppBarBlur(barBackdrop),
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        Row {
                            ExpressiveBackButton(onClick = onBack)
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barBackdrop.m3AppBarColor(),
                    scrolledContainerColor = barBackdrop.m3AppBarColor(),
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .m3BackdropLayer(barBackdrop),
            contentPadding = innerPadding,
            content = content,
        )
    }
}

/** Empty-state placeholder row for a list with no entries yet. */
@Composable
fun EmptyHint(text: String) {
    Box(Modifier.padding(vertical = 24.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
