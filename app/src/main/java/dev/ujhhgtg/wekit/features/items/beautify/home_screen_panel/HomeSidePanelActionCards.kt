package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import kotlinx.coroutines.delay

internal enum class HomeSidePanelActionPlacement {
    TILE,
    LIST_ITEM,
}

@Composable
internal fun HomeSidePanelHorizontalActionsCard(
    card: HorizontalActionsCardConfig,
    content: HomeSidePanelActionCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    actionInsertionIndex: Int? = null,
    insertionSnapshot: HomeSidePanelDragSnapshot? = null,
    actionDragModifier: (cardId: String, actionId: String) -> Modifier = { _, _ -> Modifier },
    cardDragModifier: Modifier = Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit = { _, _, _ -> },
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)? = null,
    onAddAction: (cardId: String) -> Unit = {},
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val (animatedActions, visibleActionIds) = rememberHomeSidePanelAnimatedActions(card.id, card.actions)
    val interactiveActionIds = card.actions.mapTo(mutableSetOf(), HomeSidePanelActionConfig::id)
    val showEmptyCardDelete = rememberHomeSidePanelEmptyDeleteVisibility(
        cardId = card.id,
        editMode = editMode,
        actionsEmpty = card.actions.isEmpty(),
    )
    val showAdd = editMode || content == HomeSidePanelActionCardContent.Preview
    val addVisibility = remember(card.id) { MutableTransitionState(false) }
    addVisibility.targetState = showAdd
    val (animatedGaps, visibleGapKeys) = rememberHomeSidePanelAnimatedGaps(
        cardId = card.id,
        insertionIndex = actionInsertionIndex,
        snapshot = insertionSnapshot,
    )
    val boundsTransform = rememberHomeSidePanelBoundsTransform()
    LookaheadScope {
        key(card.id) {
            FlowRow(
                modifier = modifier.fillMaxWidth().animateContentSize(tween(180)),
                maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                animatedActions.forEachIndexed { index, action ->
                    animatedGaps.filter { it.insertionIndex == index }.forEach { gap ->
                        key(gap.key) {
                            AnimatedVisibility(
                                visible = visibleGapKeys[gap.key] == true,
                                modifier = Modifier.animateBounds(
                                    lookaheadScope = this@LookaheadScope,
                                    boundsTransform = boundsTransform,
                                ),
                                enter = homeSidePanelGapEnter(horizontal = true),
                                exit = homeSidePanelGapExit(horizontal = true),
                            ) {
                                HomeSidePanelActionInsertionGap(
                                    gap.snapshot,
                                    HomeSidePanelDragAxis.Horizontal,
                                )
                            }
                        }
                    }
                    key(action.id) {
                        AnimatedVisibility(
                            visible = visibleActionIds[action.id] == true,
                            modifier = Modifier
                                .animateBounds(
                                    lookaheadScope = this@LookaheadScope,
                                    boundsTransform = boundsTransform,
                                )
                                .weight(1f),
                            enter = homeSidePanelActionEnter(horizontal = true),
                            exit = homeSidePanelActionExit(horizontal = true),
                        ) {
                            val interactive = action.id in interactiveActionIds
                            HomeSidePanelActionItem(
                                cardId = card.id,
                                action = action,
                                placement = HomeSidePanelActionPlacement.TILE,
                                content = content,
                                editMode = editMode,
                                interactive = interactive,
                                modifier = if (editMode && interactive) {
                                    actionDragModifier(card.id, action.id)
                                } else {
                                    Modifier
                                },
                                onRunAction = onRunAction,
                                onDeleteAction = onDeleteAction,
                            )
                        }
                    }
                }
                animatedGaps.filter { it.insertionIndex == animatedActions.size }.forEach { gap ->
                    key(gap.key) {
                        AnimatedVisibility(
                            visible = visibleGapKeys[gap.key] == true,
                            modifier = Modifier.animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = boundsTransform,
                            ),
                            enter = homeSidePanelGapEnter(horizontal = true),
                            exit = homeSidePanelGapExit(horizontal = true),
                        ) {
                            HomeSidePanelActionInsertionGap(
                                gap.snapshot,
                                HomeSidePanelDragAxis.Horizontal,
                            )
                        }
                    }
                }
                if (showAdd || addVisibility.currentState || !addVisibility.isIdle) {
                    key(HomeSidePanelVirtualAddKey) {
                        AnimatedVisibility(
                            visibleState = addVisibility,
                            modifier = Modifier
                                .animateBounds(
                                    lookaheadScope = this@LookaheadScope,
                                    boundsTransform = boundsTransform,
                                )
                                .weight(1f),
                            enter = homeSidePanelAddEnter(horizontal = true),
                            exit = homeSidePanelAddExit(horizontal = true),
                        ) {
                            HomeSidePanelAddActionItem(
                                placement = HomeSidePanelActionPlacement.TILE,
                                onClick = if (editMode) {
                                    { onAddAction(card.id) }
                                } else {
                                    null
                                },
                                onDeleteCard = if (showEmptyCardDelete) {
                                    onDeleteCard?.let { delete -> { delete(card.id) } }
                                } else {
                                    null
                                },
                                wholeCardDragModifier = if (editMode) cardDragModifier else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelVerticalActionsCard(
    card: VerticalActionsCardConfig,
    content: HomeSidePanelActionCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    actionInsertionIndex: Int? = null,
    insertionSnapshot: HomeSidePanelDragSnapshot? = null,
    actionDragModifier: (cardId: String, actionId: String) -> Modifier = { _, _ -> Modifier },
    cardDragModifier: Modifier = Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit = { _, _, _ -> },
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)? = null,
    onAddAction: (cardId: String) -> Unit = {},
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val showAdd = editMode || content == HomeSidePanelActionCardContent.Preview
    val (animatedActions, visibleActionIds) = rememberHomeSidePanelAnimatedActions(card.id, card.actions)
    val interactiveActionIds = card.actions.mapTo(mutableSetOf(), HomeSidePanelActionConfig::id)
    val showEmptyCardDelete = rememberHomeSidePanelEmptyDeleteVisibility(
        cardId = card.id,
        editMode = editMode,
        actionsEmpty = card.actions.isEmpty(),
    )
    val addVisibility = remember(card.id) { MutableTransitionState(false) }
    addVisibility.targetState = showAdd
    val (animatedGaps, visibleGapKeys) = rememberHomeSidePanelAnimatedGaps(
        cardId = card.id,
        insertionIndex = actionInsertionIndex,
        snapshot = insertionSnapshot,
    )
    val boundsTransform = rememberHomeSidePanelBoundsTransform()
    LookaheadScope {
        HomeSidePanelActionsCardFrame(card.id, modifier) {
            animatedActions.forEachIndexed { index, action ->
                animatedGaps.filter { it.insertionIndex == index }.forEach { gap ->
                    key(gap.key) {
                        AnimatedVisibility(
                            visible = visibleGapKeys[gap.key] == true,
                            modifier = Modifier.animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = boundsTransform,
                            ),
                            enter = homeSidePanelGapEnter(horizontal = false),
                            exit = homeSidePanelGapExit(horizontal = false),
                        ) {
                            HomeSidePanelActionInsertionGap(
                                gap.snapshot,
                                HomeSidePanelDragAxis.Vertical,
                            )
                        }
                    }
                }
                key(action.id) {
                    AnimatedVisibility(
                        visible = visibleActionIds[action.id] == true,
                        modifier = Modifier.animateBounds(
                            lookaheadScope = this@LookaheadScope,
                            boundsTransform = boundsTransform,
                        ),
                        enter = homeSidePanelActionEnter(horizontal = false),
                        exit = homeSidePanelActionExit(horizontal = false),
                    ) {
                        Column {
                            val interactive = action.id in interactiveActionIds
                            HomeSidePanelActionItem(
                                cardId = card.id,
                                action = action,
                                placement = HomeSidePanelActionPlacement.LIST_ITEM,
                                content = content,
                                editMode = editMode,
                                interactive = interactive,
                                modifier = if (editMode && interactive) {
                                    actionDragModifier(card.id, action.id)
                                } else {
                                    Modifier
                                },
                                onRunAction = onRunAction,
                                onDeleteAction = onDeleteAction,
                            )
                            if (index != animatedActions.lastIndex || showAdd) {
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }
            animatedGaps.filter { it.insertionIndex == animatedActions.size }.forEach { gap ->
                key(gap.key) {
                    AnimatedVisibility(
                        visible = visibleGapKeys[gap.key] == true,
                        modifier = Modifier.animateBounds(
                            lookaheadScope = this@LookaheadScope,
                            boundsTransform = boundsTransform,
                        ),
                        enter = homeSidePanelGapEnter(horizontal = false),
                        exit = homeSidePanelGapExit(horizontal = false),
                    ) {
                        HomeSidePanelActionInsertionGap(
                            gap.snapshot,
                            HomeSidePanelDragAxis.Vertical,
                        )
                    }
                }
            }
            if (showAdd || addVisibility.currentState || !addVisibility.isIdle) {
                key(HomeSidePanelVirtualAddKey) {
                    AnimatedVisibility(
                        visibleState = addVisibility,
                        modifier = Modifier.animateBounds(
                            lookaheadScope = this@LookaheadScope,
                            boundsTransform = boundsTransform,
                        ),
                        enter = homeSidePanelAddEnter(horizontal = false),
                        exit = homeSidePanelAddExit(horizontal = false),
                    ) {
                        HomeSidePanelAddActionItem(
                            placement = HomeSidePanelActionPlacement.LIST_ITEM,
                            onClick = if (editMode) {
                                { onAddAction(card.id) }
                            } else {
                                null
                            },
                            onDeleteCard = if (showEmptyCardDelete) {
                                onDeleteCard?.let { delete -> { delete(card.id) } }
                            } else {
                                null
                            },
                            wholeCardDragModifier = if (editMode) cardDragModifier else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelAddActionItem(
    placement: HomeSidePanelActionPlacement,
    onClick: (() -> Unit)?,
    onDeleteCard: (() -> Unit)?,
    wholeCardDragModifier: Modifier,
) {
    when (placement) {
        HomeSidePanelActionPlacement.TILE -> Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .then(wholeCardDragModifier),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.action_add), style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                HomeSidePanelCardBadge(
                    editMode = true,
                    onEdit = null,
                    onDelete = onDeleteCard,
                    deleteDescriptionRes = R.string.home_side_panel_delete_empty_action_card,
                )
            }
        }

        HomeSidePanelActionPlacement.LIST_ITEM -> Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                leadingContent = {
                    Icon(
                        MaterialSymbols.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .then(wholeCardDragModifier),
            ) {
                Text(stringResource(R.string.action_add))
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                HomeSidePanelCardBadge(
                    editMode = true,
                    onEdit = null,
                    onDelete = onDeleteCard,
                    deleteDescriptionRes = R.string.home_side_panel_delete_empty_action_card,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelActionsCardFrame(
    cardId: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    HomeSidePanelCardFrame(
        cardId = cardId,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(HOME_SIDE_PANEL_REFLOW_ANIMATION_MILLIS)),
        cardModifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        editMode = false,
        onEdit = null,
        onDelete = null,
        content = content,
    )
}

@Composable
private fun HomeSidePanelActionItem(
    cardId: String,
    action: HomeSidePanelActionConfig,
    placement: HomeSidePanelActionPlacement,
    content: HomeSidePanelActionCardContent,
    editMode: Boolean,
    interactive: Boolean,
    modifier: Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit,
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)?,
) {
    val spec = homeSidePanelActionSpec(action.kind)
    val clickModifier = if (
        interactive && !editMode && content == HomeSidePanelActionCardContent.Runtime
    ) {
        Modifier.clickable {
            onRunAction(cardId, action.id, action.kind)
        }
    } else {
        Modifier
    }
    Box(modifier = modifier.fillMaxWidth()) {
        when (placement) {
            HomeSidePanelActionPlacement.TILE -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(clickModifier),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(spec.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            HomeSidePanelActionPlacement.LIST_ITEM -> ListItem(
                leadingContent = {
                    Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = if (editMode) {
                    null
                } else {
                    {
                        Icon(MaterialSymbols.Outlined.Chevron_right, contentDescription = null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(clickModifier),
            ) {
                Text(stringResource(spec.labelRes))
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            HomeSidePanelCardBadge(
                editMode = editMode && interactive,
                onEdit = null,
                onDelete = onDeleteAction?.let { delete -> { delete(cardId, action.id) } },
                deleteDescriptionRes = R.string.home_side_panel_delete_action,
            )
        }
    }
}

private data object HomeSidePanelVirtualAddKey

private data class HomeSidePanelAnimatedGapKey(
    val startToken: Long,
    val targetChangeToken: Long,
)

private data class HomeSidePanelAnimatedGap(
    val key: HomeSidePanelAnimatedGapKey,
    val insertionIndex: Int,
    val snapshot: HomeSidePanelDragSnapshot,
)

@Composable
private fun rememberHomeSidePanelAnimatedGaps(
    cardId: String,
    insertionIndex: Int?,
    snapshot: HomeSidePanelDragSnapshot?,
): Pair<List<HomeSidePanelAnimatedGap>, Map<HomeSidePanelAnimatedGapKey, Boolean>> {
    val displayed = remember(cardId) { mutableStateListOf<HomeSidePanelAnimatedGap>() }
    val visible = remember(cardId) { mutableStateMapOf<HomeSidePanelAnimatedGapKey, Boolean>() }
    val currentKey = if (insertionIndex != null && snapshot != null) {
        HomeSidePanelAnimatedGapKey(snapshot.startToken, snapshot.targetChangeToken)
    } else {
        null
    }
    LaunchedEffect(currentKey, insertionIndex) {
        val alreadyExiting = visible.filterValues { !it }.keys
        displayed.removeAll { it.key in alreadyExiting }
        alreadyExiting.forEach(visible::remove)
        visible.keys.toList().forEach { key ->
            if (key != currentKey) visible[key] = false
        }

        var added = false
        if (currentKey != null) {
            val entry = HomeSidePanelAnimatedGap(
                key = currentKey,
                insertionIndex = checkNotNull(insertionIndex),
                snapshot = checkNotNull(snapshot),
            )
            val existingIndex = displayed.indexOfFirst { it.key == currentKey }
            if (existingIndex >= 0) {
                displayed[existingIndex] = entry
            } else {
                displayed += entry
                visible[currentKey] = false
                added = true
            }
        }

        if (added) {
            withFrameNanos { }
            visible[currentKey!!] = true
        }

        if (visible.values.any { !it }) {
            delay(HOME_SIDE_PANEL_GAP_EXIT_MILLIS.toLong())
            val hidden = visible.filterValues { !it }.keys
            displayed.removeAll { it.key in hidden }
            hidden.forEach(visible::remove)
        }
    }
    return displayed to visible
}

@Composable
private fun rememberHomeSidePanelBoundsTransform(): BoundsTransform = remember {
    BoundsTransform { _, _ -> tween(HOME_SIDE_PANEL_REFLOW_ANIMATION_MILLIS) }
}

@Composable
private fun rememberHomeSidePanelAnimatedActions(
    cardId: String,
    actions: List<HomeSidePanelActionConfig>,
): Pair<List<HomeSidePanelActionConfig>, Map<String, Boolean>> {
    val displayed = remember(cardId) { mutableStateListOf(*actions.toTypedArray()) }
    val visible = remember(cardId) {
        mutableStateMapOf<String, Boolean>().apply {
            actions.forEach { put(it.id, true) }
        }
    }
    LaunchedEffect(actions) {
        val targetIds = actions.mapTo(mutableSetOf(), HomeSidePanelActionConfig::id)
        val newIds = targetIds - displayed.mapTo(mutableSetOf(), HomeSidePanelActionConfig::id)
        val exiting = displayed.filter { it.id !in targetIds }
        val reordered = actions.toMutableList()
        exiting.forEach { action ->
            reordered.add(displayed.indexOf(action).coerceAtMost(reordered.size), action)
        }
        displayed.clear()
        displayed.addAll(reordered)
        visible.keys.toList().forEach { id -> visible[id] = id in targetIds }
        newIds.forEach { id -> visible[id] = false }
        if (newIds.isNotEmpty()) {
            withFrameNanos { }
            newIds.forEach { id -> visible[id] = true }
        }
        if (exiting.isNotEmpty()) {
            delay(HOME_SIDE_PANEL_ACTION_EXIT_MILLIS.toLong())
            val currentIds = actions.mapTo(mutableSetOf(), HomeSidePanelActionConfig::id)
            displayed.removeAll { it.id !in currentIds }
            visible.keys.filter { it !in currentIds }.forEach(visible::remove)
        }
    }
    return displayed to visible
}

@Composable
private fun rememberHomeSidePanelEmptyDeleteVisibility(
    cardId: String,
    editMode: Boolean,
    actionsEmpty: Boolean,
): Boolean {
    var visible by remember(cardId) { mutableStateOf(editMode && actionsEmpty) }
    LaunchedEffect(editMode, actionsEmpty) {
        if (!editMode || !actionsEmpty) {
            visible = false
        } else if (!visible) {
            delay(HOME_SIDE_PANEL_EMPTY_DELETE_HANDOFF_MILLIS.toLong())
            visible = true
        }
    }
    return visible
}

private fun homeSidePanelActionEnter(horizontal: Boolean) =
    fadeIn(tween(150)) + scaleIn(tween(180), initialScale = 0.9f) + if (horizontal) {
        expandHorizontally(tween(180), expandFrom = Alignment.CenterHorizontally)
    } else {
        expandVertically(tween(180), expandFrom = Alignment.Top)
    }

private fun homeSidePanelActionExit(horizontal: Boolean) =
    fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.88f) + if (horizontal) {
        shrinkHorizontally(tween(HOME_SIDE_PANEL_ACTION_EXIT_MILLIS), shrinkTowards = Alignment.CenterHorizontally)
    } else {
        shrinkVertically(tween(HOME_SIDE_PANEL_ACTION_EXIT_MILLIS), shrinkTowards = Alignment.Top)
    }

private fun homeSidePanelGapEnter(horizontal: Boolean) =
    fadeIn(tween(120)) + if (horizontal) {
        expandHorizontally(tween(HOME_SIDE_PANEL_GAP_EXIT_MILLIS), expandFrom = Alignment.Start)
    } else {
        expandVertically(tween(HOME_SIDE_PANEL_GAP_EXIT_MILLIS), expandFrom = Alignment.Top)
    }

private fun homeSidePanelGapExit(horizontal: Boolean) =
    fadeOut(tween(100)) + if (horizontal) {
        shrinkHorizontally(tween(HOME_SIDE_PANEL_GAP_EXIT_MILLIS), shrinkTowards = Alignment.Start)
    } else {
        shrinkVertically(tween(HOME_SIDE_PANEL_GAP_EXIT_MILLIS), shrinkTowards = Alignment.Top)
    }

private fun homeSidePanelAddEnter(horizontal: Boolean) =
    fadeIn(tween(160)) + if (horizontal) {
        expandHorizontally(tween(200), expandFrom = Alignment.Start)
    } else {
        expandVertically(tween(200), expandFrom = Alignment.Top)
    }

private fun homeSidePanelAddExit(horizontal: Boolean) =
    fadeOut(tween(120)) + if (horizontal) {
        shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start)
    } else {
        shrinkVertically(tween(180), shrinkTowards = Alignment.Top)
    }

private const val HOME_SIDE_PANEL_ACTION_EXIT_MILLIS = 170
private const val HOME_SIDE_PANEL_GAP_EXIT_MILLIS = 180
private const val HOME_SIDE_PANEL_EMPTY_DELETE_HANDOFF_MILLIS = 200
private const val HOME_SIDE_PANEL_REFLOW_ANIMATION_MILLIS = 180
