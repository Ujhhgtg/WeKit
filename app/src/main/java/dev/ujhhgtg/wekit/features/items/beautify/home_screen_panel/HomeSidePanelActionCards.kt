package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R

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
    actionDragModifier: (cardId: String, actionId: String) -> Modifier = { _, _ -> Modifier },
    cardDragModifier: Modifier = Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit = { _, _, _ -> },
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)? = null,
    onAddAction: (cardId: String) -> Unit = {},
    onDeleteCard: ((String) -> Unit)? = null,
) {
    key(card.id) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            maxItemsInEachRow = 3,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            card.actions.forEach { action ->
                key(action.id) {
                    Box(modifier = Modifier.weight(1f)) {
                        HomeSidePanelActionItem(
                            cardId = card.id,
                            action = action,
                            placement = HomeSidePanelActionPlacement.TILE,
                            content = content,
                            editMode = editMode,
                            modifier = if (editMode) actionDragModifier(card.id, action.id) else Modifier,
                            onRunAction = onRunAction,
                            onDeleteAction = onDeleteAction,
                        )
                    }
                }
            }
            if (editMode || content == HomeSidePanelActionCardContent.Preview) {
                key(HomeSidePanelVirtualAddKey) {
                    Box(modifier = Modifier.weight(1f)) {
                        HomeSidePanelAddActionItem(
                            placement = HomeSidePanelActionPlacement.TILE,
                            onClick = if (editMode) {
                                { onAddAction(card.id) }
                            } else {
                                null
                            },
                            onDeleteCard = if (editMode && card.actions.isEmpty()) {
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
internal fun HomeSidePanelVerticalActionsCard(
    card: VerticalActionsCardConfig,
    content: HomeSidePanelActionCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    actionDragModifier: (cardId: String, actionId: String) -> Modifier = { _, _ -> Modifier },
    cardDragModifier: Modifier = Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit = { _, _, _ -> },
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)? = null,
    onAddAction: (cardId: String) -> Unit = {},
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val showAdd = editMode || content == HomeSidePanelActionCardContent.Preview
    HomeSidePanelActionsCardFrame(card.id, modifier) {
        card.actions.forEachIndexed { index, action ->
            key(action.id) {
                HomeSidePanelActionItem(
                    cardId = card.id,
                    action = action,
                    placement = HomeSidePanelActionPlacement.LIST_ITEM,
                    content = content,
                    editMode = editMode,
                    modifier = if (editMode) actionDragModifier(card.id, action.id) else Modifier,
                    onRunAction = onRunAction,
                    onDeleteAction = onDeleteAction,
                )
                if (index != card.actions.lastIndex || showAdd) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
        if (showAdd) {
            key(HomeSidePanelVirtualAddKey) {
                HomeSidePanelAddActionItem(
                    placement = HomeSidePanelActionPlacement.LIST_ITEM,
                    onClick = if (editMode) {
                        { onAddAction(card.id) }
                    } else {
                        null
                    },
                    onDeleteCard = if (editMode && card.actions.isEmpty()) {
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

@Composable
internal fun HomeSidePanelAddActionItem(
    placement: HomeSidePanelActionPlacement,
    onClick: (() -> Unit)?,
    onDeleteCard: (() -> Unit)?,
    wholeCardDragModifier: Modifier,
) {
    when (placement) {
        HomeSidePanelActionPlacement.TILE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(wholeCardDragModifier),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
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
            modifier = Modifier
                .fillMaxWidth()
                .then(wholeCardDragModifier),
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
                    .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
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
        modifier = modifier.fillMaxWidth(),
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
    modifier: Modifier,
    onRunAction: (cardId: String, actionId: String, kind: HomeSidePanelActionKind) -> Unit,
    onDeleteAction: ((cardId: String, actionId: String) -> Unit)?,
) {
    val spec = homeSidePanelActionSpec(action.kind)
    val clickModifier = if (!editMode && content == HomeSidePanelActionCardContent.Runtime) {
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
                editMode = editMode,
                onEdit = null,
                onDelete = onDeleteAction?.let { delete -> { delete(cardId, action.id) } },
                deleteDescriptionRes = R.string.home_side_panel_delete_action,
            )
        }
    }
}

private data object HomeSidePanelVirtualAddKey
