package dev.ujhhgtg.wekit.ui.utils

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/** 长按拖拽手柄调整列表顺序（ChatToolbar 设置同款实现）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> ReorderableList(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        userScrollEnabled = draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) },
        ) { _, item ->
            val key = itemKey(item)
            val isDragging = draggingKey == key
            val dragHandleModifier = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
                            draggingKey = key
                            dragOffset = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingKey != key) return@detectDragGesturesAfterLongPress
                        dragOffset += amount.y

                        val currentInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }
                            ?: return@detectDragGesturesAfterLongPress
                        val currentIndex = currentInfo.index
                        val start = currentInfo.offset + dragOffset
                        val end = start + currentInfo.size
                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                            if (targetInfo.index == currentIndex) {
                                false
                            } else if (dragOffset > 0f) {
                                targetInfo.index > currentIndex &&
                                        end > targetInfo.offset + targetInfo.size / 2
                            } else {
                                targetInfo.index < currentIndex &&
                                        start < targetInfo.offset + targetInfo.size / 2
                            }
                        }
                        if (target != null) {
                            onMove(currentIndex, target.index)
                            dragOffset -= target.offset - currentInfo.offset
                        }

                        val viewport = listState.layoutInfo
                        val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                        when {
                            center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                coroutineScope.launch { listState.scrollBy(-12f) }

                            center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                coroutineScope.launch { listState.scrollBy(12f) }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        scaleX = if (isDragging) 1.02f else 1f
                        scaleY = if (isDragging) 1.02f else 1f
                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                    }
                    .then(if (isDragging) Modifier else Modifier.animateItem())
            ) {
                itemContent(item, dragHandleModifier)
            }
        }
    }
}
