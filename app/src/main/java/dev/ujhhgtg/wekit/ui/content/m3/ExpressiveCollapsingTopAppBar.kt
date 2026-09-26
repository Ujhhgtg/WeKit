package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** A single title moves and changes size with the bar, without crossing a clipped second row. */
@Composable
fun ExpressiveCollapsingTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
) {
    val state = scrollBehavior.state
    val fraction = state.collapsedFraction
    val expandedStyle = MaterialTheme.typography.displaySmall.copy(textMotion = TextMotion.Animated)
    val collapsedStyle = MaterialTheme.typography.titleLarge.copy(textMotion = TextMotion.Animated)
    val textMeasurer = rememberTextMeasurer()
    // Endpoint metrics keep the scroll range stable while the visible title changes size. They
    // also allow the bar to grow with accessibility font scaling instead of clipping the text.
    val expandedTitle = textMeasurer.measure(title, expandedStyle, softWrap = false, maxLines = 1)
    val collapsedTitle = textMeasurer.measure(title, collapsedStyle, softWrap = false, maxLines = 1)

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { delta -> state.heightOffset += delta },
                orientation = Orientation.Vertical,
                enabled = !scrollBehavior.isPinned,
                onDragStopped = { velocity ->
                    // Let the existing behavior perform its configured fling and snap.
                    scrollBehavior.nestedScrollConnection.onPostFling(
                        consumed = Velocity.Zero,
                        available = Velocity(0f, velocity),
                    )
                },
            )
            .drawBehind {
                drawRect(lerp(colors.containerColor, colors.scrolledContainerColor, state.collapsedFraction))
            }
            .semantics { isTraversalGroup = true }
            .pointerInput(Unit) {}
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .clipToBounds(),
        content = {
            Box {
                CompositionLocalProvider(LocalContentColor provides colors.navigationIconContentColor) {
                    navigationIcon()
                }
            }
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = colors.titleContentColor,
                style = lerp(expandedStyle, collapsedStyle, fraction),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides colors.actionIconContentColor) {
                    actions()
                }
            }
        },
    ) { measurables, constraints ->
        val iconPadding = 4.dp.roundToPx()
        val titlePadding = 16.dp.roundToPx()
        val iconConstraints = constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxWidth = (constraints.maxWidth - 2 * iconPadding).coerceAtLeast(0),
        )
        val navigation = measurables[0].measure(iconConstraints)
        val actionIcons = measurables[2].measure(iconConstraints)
        val collapsedHeight = maxOf(
            TopAppBarDefaults.LargeAppBarCollapsedHeight.roundToPx(),
            collapsedTitle.size.height,
            navigation.height,
            actionIcons.height,
        )
        val expandedBottomPadding =
            (28.dp.toPx() - (expandedTitle.size.height - expandedTitle.lastBaseline)).coerceAtLeast(0f)
        val expandedHeight = maxOf(
            TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight.roundToPx(),
            collapsedHeight + expandedTitle.size.height + expandedBottomPadding.roundToInt(),
        )
        val collapseRange = (expandedHeight - collapsedHeight).toFloat()
        if (state.heightOffsetLimit != -collapseRange) {
            val previousFraction = state.collapsedFraction
            state.heightOffsetLimit = -collapseRange
            state.heightOffset = -collapseRange * previousFraction
        }

        val collapsedStart = maxOf(titlePadding, navigation.width + 2 * iconPadding)
        val collapsedEnd = maxOf(titlePadding, actionIcons.width + 2 * iconPadding)
        // Move into the space between the buttons early, before the title reaches their row.
        val horizontalFraction = LinearOutSlowInEasing.transform(fraction)
        val titleStart = lerp(titlePadding.toFloat(), collapsedStart.toFloat(), horizontalFraction)
        val titleEnd = lerp(titlePadding.toFloat(), collapsedEnd.toFloat(), horizontalFraction)
        val titlePlaceable = measurables[1].measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = (constraints.maxWidth - titleStart - titleEnd).roundToInt().coerceAtLeast(0),
            ),
        )
        val height = constraints.constrainHeight((expandedHeight + state.heightOffset).roundToInt())
        val expandedTitleY = expandedHeight - expandedBottomPadding - expandedTitle.size.height
        val collapsedTitleY = (collapsedHeight - collapsedTitle.size.height) / 2f
        val titleY = lerp(expandedTitleY, collapsedTitleY, fraction)

        layout(constraints.maxWidth, height) {
            navigation.placeRelative(iconPadding, (collapsedHeight - navigation.height) / 2)
            titlePlaceable.placeRelativeWithLayer(0, 0) {
                translationX = if (layoutDirection == LayoutDirection.Ltr) titleStart else -titleStart
                translationY = titleY
            }
            actionIcons.placeRelative(
                constraints.maxWidth - iconPadding - actionIcons.width,
                (collapsedHeight - actionIcons.height) / 2,
            )
        }
    }
}
