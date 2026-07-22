package dev.ujhhgtg.wekit.ui.panel

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelUiState
import dev.ujhhgtg.wekit.ui.utils.CommonContextWrapper
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import kotlinx.coroutines.delay

data class PanelRailItem<T>(
    val destination: T,
    val icon: ImageVector,
    val label: String,
)

data class PanelAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val showLabel: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

data class PanelActionSearch(
    val expanded: Boolean,
    val value: String,
    val label: String,
    val actionIndex: Int = Int.MAX_VALUE,
    val onValueChange: (String) -> Unit,
    val onExpandedChange: (Boolean) -> Unit,
)

@Composable
private fun PanelHeaderAction(
    action: PanelAction?,
    edge: Alignment.Horizontal,
    animationLabel: String,
) {
    val direction = if (edge == Alignment.Start) -1 else 1
    AnimatedContent(
        targetState = action,
        contentKey = { it != null },
        contentAlignment = if (edge == Alignment.Start) Alignment.CenterStart else Alignment.CenterEnd,
        transitionSpec = {
            val enter = fadeIn(tween(180, delayMillis = 40)) +
                    scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.78f) +
                    slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) {
                        direction * it / 3
                    }
            val exit = fadeOut(tween(150)) +
                    scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.78f) +
                    slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) {
                        direction * it / 3
                    }
            (enter togetherWith exit).using(
                SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ -> tween(260, easing = FastOutSlowInEasing) },
                ),
            )
        },
        label = animationLabel,
    ) { currentAction ->
        if (currentAction == null) {
            Box(Modifier)
        } else {
            IconButton(onClick = currentAction.onClick, enabled = currentAction.enabled) {
                Icon(currentAction.icon, currentAction.label)
            }
        }
    }
}

internal data class PanelImportOption<T>(
    val mode: T,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

class PanelDialogScope internal constructor(private val dialog: Dialog) {
    fun dismiss() = dialog.dismiss()
}

@Suppress("DEPRECATION")
fun showPanelDialog(
    context: Context,
    onDismiss: () -> Unit = {},
    content: @Composable PanelDialogScope.() -> Unit,
) {
    val wrapped = CommonContextWrapper(context)
    // ComponentDialog supplies both back-dispatcher owners required by the current
    // activity-compose BackHandler implementation. A plain Dialog has neither owner
    // in an injected WeChat window and crashes as soon as BackHandler enters composition.
    val dialog = ComponentDialog(wrapped, android.R.style.Theme_Translucent_NoTitleBar)
    val scope = PanelDialogScope(dialog)

    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        WindowCompat.setDecorFitsSystemWindows(this, false)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
        navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isStatusBarContrastEnforced = false
            isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(this, decorView).apply {
            isAppearanceLightStatusBars = !wrapped.isDarkMode
            isAppearanceLightNavigationBars = !wrapped.isDarkMode
        }
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.3f)
        setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        attributes = attributes.apply { gravity = Gravity.BOTTOM }
    }
    dialog.setCancelable(true)
    dialog.setContentView(
        ComposeView(wrapped).apply {
            setContent {
                CompositionLocalProvider(LocalContext provides wrapped) {
                    InjectedUiTheme {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .clickable(
                                    indication = null,
                                    interactionSource = null,
                                    onClick = scope::dismiss,
                                ),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.65f)
                                    .clickable(
                                        indication = null,
                                        interactionSource = null,
                                        onClick = {},
                                    ),
                            ) {
                                scope.content()
                            }
                        }
                    }
                }
            }
        },
    )
    dialog.setOnDismissListener {
        onDismiss()
    }
    dialog.show()
    dialog.window?.setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
    )
}

@Composable
internal fun <T> PanelImportModePrompt(
    options: List<PanelImportOption<T>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    PanelFullOverlay(onDismiss) {
        Text("选择导入方式", style = MaterialTheme.typography.titleMedium)
        options.forEach { option ->
            ListItem(
                modifier = Modifier.clickable { onSelect(option.mode) },
                colors = panelListItemColors(),
                headlineContent = { Text(option.title) },
                supportingContent = { Text(option.description) },
                leadingContent = { Icon(option.icon, null) },
            )
        }
    }
}

@Composable
fun <T> PanelShell(
    railItems: List<PanelRailItem<T>>,
    selected: T,
    title: String,
    actions: List<PanelAction> = emptyList(),
    actionSearch: PanelActionSearch? = null,
    wrapActions: Boolean = false,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    fun PanelAction.isHeaderAction(): Boolean =
        icon == MaterialSymbols.Outlined.Arrow_back || icon == MaterialSymbols.Outlined.Refresh

    val backAction = actions.firstOrNull { it.icon == MaterialSymbols.Outlined.Arrow_back }
    val refreshAction = actions.firstOrNull { it.icon == MaterialSymbols.Outlined.Refresh }
    val stripActions = actions.filterNot { it.isHeaderAction() }
    val stripActionSearch = actionSearch?.let { search ->
        if (search.actionIndex == Int.MAX_VALUE) search
        else {
            val originalIndex = search.actionIndex.coerceIn(0, actions.size)
            val removedBeforeSearch = actions.take(originalIndex).count { it.isHeaderAction() }
            search.copy(actionIndex = (originalIndex - removedBeforeSearch).coerceAtLeast(0))
        }
    }
    val titleStartPadding by animateDpAsState(
        targetValue = if (backAction == null) 12.dp else 48.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "panel-title-start-padding",
    )

    fun closeActionSearch() {
        actionSearch?.onValueChange?.invoke("")
        actionSearch?.onExpandedChange?.invoke(false)
    }
    BackHandler {
        if (actionSearch?.expanded == true) closeActionSearch() else onBack()
    }
    val railListState = rememberLazyListState()
    val selectedRailIndex = railItems.indexOfFirst { it.destination == selected }
    val selectedRailOffset by remember(railListState, selectedRailIndex) {
        derivedStateOf {
            railListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == selectedRailIndex }
                ?.offset
        }
    }
    val indicatorOffset = remember { Animatable(0f) }
    var indicatorPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(selectedRailOffset, railListState.isScrollInProgress) {
        val target = selectedRailOffset?.toFloat() ?: return@LaunchedEffect
        if (!indicatorPositioned || railListState.isScrollInProgress) {
            indicatorOffset.snapTo(target)
            indicatorPositioned = true
        } else {
            indicatorOffset.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            )
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)),
            ) {
                if (selectedRailOffset != null) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer { translationY = indicatorOffset.value }
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
                LazyColumn(
                    state = railListState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(railItems) { item ->
                        val isSelected = item.destination == selected
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clickable {
                                    if (actionSearch?.expanded == true) closeActionSearch()
                                    onSelect(item.destination)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = titleStartPadding, end = 100.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (titleContent == null) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                AnimatedContent(
                                    targetState = title,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart,
                                    transitionSpec = {
                                        val enter = fadeIn(tween(180, delayMillis = 50)) +
                                                slideInVertically(
                                                    tween(220, easing = FastOutSlowInEasing),
                                                ) { it / 4 }
                                        val exit = fadeOut(tween(130)) +
                                                slideOutVertically(
                                                    tween(180, easing = FastOutSlowInEasing),
                                                ) { -it / 4 }
                                        (enter togetherWith exit).using(SizeTransform(clip = false))
                                    },
                                    label = "panel-title",
                                ) { animatedTitle ->
                                    Text(
                                        text = animatedTitle,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            titleContent()
                            Box(Modifier.weight(1f))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PanelHeaderAction(backAction, Alignment.Start, "panel-header-back")
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterEnd) {
                            PanelHeaderAction(refreshAction, Alignment.End, "panel-header-refresh")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(MaterialSymbols.Outlined.Close, "关闭")
                        }
                    }
                }
                HorizontalDivider()
                if (stripActions.isNotEmpty() || stripActionSearch != null) {
                    PanelActionStrip(stripActions, wrapActions, stripActionSearch)
                    HorizontalDivider()
                }
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelActionStrip(
    actions: List<PanelAction>,
    wrapActions: Boolean,
    search: PanelActionSearch?,
) {
    val density = LocalDensity.current
    var stripPosition by remember { mutableStateOf(Offset.Zero) }
    var searchCenterInWindow by remember { mutableStateOf<Offset?>(null) }
    var stripWidthPx by remember { mutableStateOf(1f) }
    val originFraction = searchCenterInWindow
        ?.let { ((it.x - stripPosition.x) / stripWidthPx).coerceIn(0f, 1f) }
        ?: 0.5f
    val originYOffsetPx = searchCenterInWindow
        ?.let {
            val firstRowCenter = with(density) { 24.dp.toPx() }
            (it.y - stripPosition.y - firstRowCenter).coerceAtLeast(0f)
        }
        ?: 0f
    val progress by animateFloatAsState(
        targetValue = if (search?.expanded == true) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "panel-action-search",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                stripPosition = coordinates.positionInWindow()
                stripWidthPx = coordinates.size.width.toFloat().coerceAtLeast(1f)
            },
    ) {
        // AnimatedContent only drives the strip height. The search surface is a separate
        // overlay so it remains composed for the complete reverse animation instead of
        // being replaced by the regular actions as soon as expanded becomes false.
        AnimatedContent(
            targetState = search?.expanded == true,
            transitionSpec = {
                val contentTransition = if (targetState) {
                    EnterTransition.None togetherWith fadeOut(tween(220))
                } else {
                    fadeIn(tween(220)) togetherWith ExitTransition.None
                }
                contentTransition.using(
                    SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(320) }),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            label = "panel-action-search-height",
        ) { searching ->
            if (searching) {
                Box(Modifier.fillMaxWidth().height(48.dp))
            } else {
                PanelActionItems(
                    actions = actions,
                    wrapActions = wrapActions,
                    search = search,
                    onSearchPositioned = { coordinates ->
                        val position = coordinates.positionInWindow()
                        searchCenterInWindow = Offset(
                            position.x + coordinates.size.width / 2f,
                            position.y + coordinates.size.height / 2f,
                        )
                    },
                )
            }
        }
        if (search != null && (search.expanded || progress > 0f)) {
            PanelActionSearchField(
                search = search,
                progress = progress,
                originFraction = originFraction,
                originYOffsetPx = originYOffsetPx,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelActionItems(
    actions: List<PanelAction>,
    wrapActions: Boolean,
    search: PanelActionSearch?,
    onSearchPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
) {
    val searchIndex = search?.actionIndex?.coerceIn(0, actions.size)
    val content: @Composable () -> Unit = {
        for (index in 0..actions.size) {
            search?.takeIf { index == searchIndex }?.let {
                PanelActionSearchButton(it, onSearchPositioned)
            }
            if (index < actions.size) PanelActionItem(actions[index])
        }
    }
    if (wrapActions) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            content()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun PanelActionSearchButton(
    search: PanelActionSearch,
    onPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .onGloballyPositioned(onPositioned),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = { search.onExpandedChange(true) }) {
            Icon(MaterialSymbols.Outlined.Search, search.label)
        }
    }
}

@Composable
private fun PanelActionSearchField(
    search: PanelActionSearch,
    progress: Float,
    originFraction: Float,
    originYOffsetPx: Float,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    LaunchedEffect(search.expanded) {
        if (search.expanded) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val iconStartPx = (originFraction * widthPx - with(density) { 24.dp.toPx() }).coerceAtLeast(0f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = progress.coerceAtLeast(0.01f)
                    transformOrigin = TransformOrigin(originFraction, 0.5f)
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    search.onValueChange("")
                    search.onExpandedChange(false)
                },
                modifier = Modifier.graphicsLayer {
                    translationX = iconStartPx * (1f - progress)
                    translationY = originYOffsetPx * (1f - progress)
                },
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = progress)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MaterialSymbols.Outlined.Search, "关闭搜索")
                }
            }
            BasicTextField(
                value = search.value,
                onValueChange = search.onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .graphicsLayer { alpha = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (search.value.isEmpty()) {
                            Text(
                                search.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun PanelActionItem(action: PanelAction) {
    Box(
        modifier = Modifier.height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (action.showLabel) {
            if (action.onLongClick == null) {
                TextButton(onClick = action.onClick, enabled = action.enabled) {
                    Icon(action.icon, action.label, Modifier.size(20.dp))
                    Text(action.label, Modifier.padding(start = 6.dp))
                }
            } else {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 64.dp, minHeight = 40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = action.enabled,
                                onClick = action.onClick,
                                onLongClick = action.onLongClick,
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(action.icon, action.label, Modifier.size(20.dp))
                        Text(
                            text = action.label,
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        } else {
            IconButton(onClick = action.onClick, enabled = action.enabled) {
                Icon(action.icon, action.label)
            }
        }
    }
}

@Composable
fun <T> PanelStateContent(
    state: PanelUiState<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        PanelUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is PanelUiState.Content -> content(state.value)
        is PanelUiState.Empty -> PanelMessage(state.message)
        is PanelUiState.Error -> PanelMessage(state.message, onRetry)
    }
}

@Composable
private fun PanelMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
fun PanelEmptyAction(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun PanelTextPrompt(
    title: String,
    label: String,
    initialValue: String = "",
    confirmText: String = "确定",
    allowBlank: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = allowBlank || value.isNotBlank()) {
                Text(confirmText)
            }
        }
    }
}

@Composable
fun PanelNumberPrompt(
    title: String,
    label: String,
    initialValue: Long,
    minValue: Long,
    maxValue: Long = Long.MAX_VALUE,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsed = value.toLongOrNull()
    val error = when {
        value.isBlank() || parsed == null -> "请输入有效的整数"
        parsed < minValue -> "不能小于 $minValue"
        parsed > maxValue -> "不能大于 $maxValue"
        else -> null
    }
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            supportingText = error?.let { message -> { Text(message) } },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = error == null) { Text("确定") }
        }
    }
}

@Composable
fun PanelConfirmation(
    title: String,
    message: String,
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PanelOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = onConfirm) { Text(confirmText) }
        }
    }
}

@Composable
fun PanelProgressOverlay(message: String, progress: Float? = null) {
    PanelOverlay(onDismiss = {}) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (progress == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
fun PanelFullOverlay(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .clickable(indication = null, interactionSource = null, onClick = {}),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
fun PanelPageOverlay(
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clickable(indication = null, interactionSource = null, onClick = {}),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PanelOverlay(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = PanelFullOverlay(onDismiss, content)
