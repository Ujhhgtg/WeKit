package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Notifications_off
import com.composables.icons.materialsymbols.outlinedfilled.Notifications
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.InfoTipCard
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Page list in the style of InstallerX-Revived's smart-authorizer sheet: segmented rows, long-press
 * anywhere to drag, tap a row to toggle it. Shares its drag with the bar.
 */
@Composable
fun PageManagementPage(session: NavBarSession, config: NavBarConfig) {
    val localizedContext = LocalLocalizedContext.current
    val haptic = LocalHapticFeedback.current
    val darkTheme = isSystemInDarkTheme()
    val nativeIcons =
        remember(config.iconStyle, darkTheme) {
            if (config.iconStyle == NavIconStyle.NATIVE) session.nativeIcons else null
        }
    val showBadgeToggles = config.style != NavBarStyle.HIDDEN
    val reorder = session.reorder
    val order = config.order
    val latestOrder by rememberUpdatedState(order)
    val gapPx = with(LocalDensity.current) { ListItemDefaults.SegmentedGap.toPx() }
    var rowStep by remember { mutableFloatStateOf(0f) }

    fun toggleEnabled(tab: Int, enabled: Boolean) {
        val current = BeautifyNavigationBar.config
        if (!enabled && tab in current.enabledTabs && current.enabledTabs.size <= 1) {
            showToast(localizedContext.getString(R.string.nav_page_keep_one))
            return
        }
        BeautifyNavigationBar.updateConfig {
            copy(enabledTabs = if (enabled) enabledTabs + tab else enabledTabs - tab)
        }
    }

    InfoTipCard(
        text = stringResource(R.string.nav_page_management_hint),
        noPadding = true,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    Column(
        modifier =
            Modifier.fillMaxWidth().pointerInput(rowStep) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (rowStep <= 0f) return@detectDragGesturesAfterLongPress
                        val index = (position.y / rowStep).toInt()
                        if (index in latestOrder.indices) {
                            reorder.start(latestOrder[index], ReorderSource.LIST, index)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, amount ->
                        val tab = reorder.draggedTab
                        if (tab == null || reorder.source != ReorderSource.LIST)
                            return@detectDragGesturesAfterLongPress
                        change.consume()
                        reorder.fingerOffset += amount.y
                        val from = latestOrder.indexOf(tab)
                        val target =
                            reorderTarget(
                                from,
                                reorder.fingerOffset,
                                rowStep,
                                latestOrder.lastIndex,
                            )
                        if (target != reorder.targetIndex) {
                            reorder.targetIndex = target
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    onDragEnd = {
                        if (reorder.source == ReorderSource.LIST) commitReorder(session)
                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                    onDragCancel = {
                        if (reorder.source == ReorderSource.LIST) reorder.reset()
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        order.forEachIndexed { index, tabIndex ->
            key(tabIndex) {
                val tab = navTab(tabIndex)
                val label = stringResource(tab.labelRes)
                val enabled = tabIndex in config.enabledTabs
                val dragged = reorder.draggedTab == tabIndex
                val offset =
                    rememberReorderOffset(
                        slot = index,
                        step = rowStep,
                        target =
                            reorderShift(
                                reorder,
                                order,
                                { true },
                                tabIndex,
                                rowStep,
                                ReorderSource.LIST,
                            ),
                        followFinger = reorder.follows(tabIndex, ReorderSource.LIST),
                    )

                // Feed the drag into the item's interaction source so SegmentedListItem shows
                // its own dragged elevation and state layer, whichever surface is dragging.
                val interactionSource = remember { MutableInteractionSource() }
                var dragInteraction by remember { mutableStateOf<DragInteraction.Start?>(null) }
                LaunchedEffect(dragged) {
                    if (dragged) {
                        val interaction = DragInteraction.Start()
                        interactionSource.emit(interaction)
                        dragInteraction = interaction
                    } else {
                        dragInteraction?.let { interactionSource.emit(DragInteraction.Stop(it)) }
                        dragInteraction = null
                    }
                }

                SegmentedListItem(
                    onClick = { toggleEnabled(tabIndex, !enabled) },
                    shapes = ListItemDefaults.segmentedShapes(index, order.size),
                    colors =
                        ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            leadingContentColor = MaterialTheme.colorScheme.primary,
                        ),
                    elevation = ListItemDefaults.elevation(0.dp, 6.dp),
                    interactionSource = interactionSource,
                    modifier =
                        Modifier.onGloballyPositioned {
                                if (rowStep == 0f) rowStep = it.size.height + gapPx
                            }
                            .zIndex(if (dragged) 10f else if (offset.value != 0f) 5f else 0f)
                            .graphicsLayer { translationY = offset.value },
                    leadingContent = {
                        if (nativeIcons != null) {
                            Image(
                                nativeIcons.getValue(tabIndex).outlined,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Icon(tab.outlined, contentDescription = null)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showBadgeToggles) {
                                if (tab.supportsBadge) {
                                    val badged = tabIndex in config.badgeTabs
                                    IconToggleButton(
                                        checked = badged,
                                        onCheckedChange = { value ->
                                            BeautifyNavigationBar.updateConfig {
                                                copy(
                                                    badgeTabs =
                                                        if (value) badgeTabs + tabIndex
                                                        else badgeTabs - tabIndex,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            if (badged) MaterialSymbols.OutlinedFilled.Notifications
                                            else MaterialSymbols.Outlined.Notifications_off,
                                            contentDescription =
                                                stringResource(
                                                    R.string.nav_badge_toggle_description,
                                                    label,
                                                ),
                                        )
                                    }
                                } else {
                                    // An invisible twin keeps the Me row's height and
                                    // checkbox column identical to the others.
                                    IconToggleButton(
                                        checked = false,
                                        onCheckedChange = {},
                                        enabled = false,
                                        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Notifications_off,
                                            contentDescription = null,
                                        )
                                    }
                                }
                            }
                            Checkbox(
                                checked = enabled,
                                onCheckedChange = { value -> toggleEnabled(tabIndex, value) },
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    },
                    content = { Text(label) },
                )
            }
        }
    }
}
