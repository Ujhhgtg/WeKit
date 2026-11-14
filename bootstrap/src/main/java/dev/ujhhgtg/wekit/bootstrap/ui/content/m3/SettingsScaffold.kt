package dev.ujhhgtg.wekit.bootstrap.ui.content.m3

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.bootstrap.ui.content.rememberMaterial3BlurBackdrop

@Composable
fun SettingsScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val barBackdrop = rememberMaterial3BlurBackdrop()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ExpressiveCollapsingTopAppBar(
                modifier = Modifier.m3AppBarBlur(barBackdrop),
                title = title,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = barBackdrop.m3AppBarColor(),
                        scrolledContainerColor = barBackdrop.m3AppBarColor(),
                    ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().m3BackdropLayer(barBackdrop),
            contentPadding = innerPadding,
            content = content,
        )
    }
}
