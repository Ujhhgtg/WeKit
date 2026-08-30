// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun <T> LazyListScope.lazySegmentedItems(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    itemsIndexed(
        items = items,
        key = { _, item -> key(item) },
    ) { index, item ->
        val targetTop = if (index == 0) CornerRadius else ConnectionRadius
        val targetBottom = if (index == items.lastIndex) CornerRadius else ConnectionRadius
        val top by animateDpAsState(
            targetValue = targetTop,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "segmentedTopRadius",
        )
        val bottom by animateDpAsState(
            targetValue = targetBottom,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "segmentedBottomRadius",
        )
        val shape = RoundedCornerShape(
            topStart = top,
            topEnd = top,
            bottomStart = bottom,
            bottomEnd = bottom,
        )

        Box(
            Modifier
                .animateItem()
                .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
        ) {
            CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                itemContent(item)
            }
        }
    }
}
