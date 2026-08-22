@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
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

/**
 * Full-viewport empty state for an agent settings list: centered title, optional message,
 * and optional filled action button.
 */
@Composable
fun LazyItemScope.AgentEmptyState(
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillParentMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** Two-button confirmation dialog; [destructive] tints the confirm action with the error color. */
@Composable
fun AgentConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    destructive: Boolean = false,
    loading: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = confirmLabel,
                        color = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(dismissLabel) }
        },
    )
}

/**
 * Back guard shared by the detail screens' creation mode: while [guardActive] (a savable but
 * unsaved draft), every back attempt — scaffold back button or system gesture — opens a
 * confirm-discard dialog instead of leaving; otherwise back passes straight through. Returns the
 * guarded callback to hand to [AgentSettingsScaffold]'s onBack.
 */
@Composable
fun rememberCreationBackGuard(guardActive: Boolean, onBack: () -> Unit): () -> Unit {
    var showDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = guardActive) { showDiscard = true }
    AgentConfirmDialog(
        show = showDiscard,
        title = stringResource(R.string.agent_discard_creation_title),
        message = stringResource(R.string.agent_discard_creation_message),
        confirmLabel = stringResource(R.string.agent_discard_creation_confirm),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDiscard = false
            onBack()
        },
        onDismiss = { showDiscard = false },
    )
    return { if (guardActive) showDiscard = true else onBack() }
}

/** Compact list action button with a leading icon that swaps to a progress spinner while loading. */
@Composable
fun AgentListActionButton(
    label: String,
    icon: ImageVector,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

/** Horizontal wrapper for paired list actions, applying content padding and the bottom inset. */
@Composable
fun AgentActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Full-height editor sheet: scrolling body content plus a fixed bottom action bar. */
@Composable
fun AgentEditorSheet(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                content = content,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp),
            ) {
                bottomBar()
            }
        }
    }
}
