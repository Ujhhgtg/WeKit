package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.view.MotionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelGestureStateTest {

    @Test
    fun fullOneShotCloseUsesTheApprovedShorterDuration() {
        assertEquals(240L, homeSidePanelOneShotCloseDuration(from = 1f, target = 0f))
    }

    @Test
    fun gestureThresholdsUseDensityScaledPixels() {
        val config = homeSidePanelGestureConfig(density = 3f)

        assertEquals(24f, config.touchSlopPx)
    }

    @Test
    fun homeTabHorizontalEdgeDragSettlesOpen() {
        val state = HomeSidePanelGestureState(
            HomeSidePanelGestureConfig(touchSlopPx = 8f)
        )
        state.setSelectedTab(0)
        state.onDown(x = 4f, y = 200f, widthPx = 400f, timeMs = 0)

        val decision = state.onMove(x = 180f, y = 205f, timeMs = 16)
        assertEquals(HomeSidePanelGestureDecision.CONSUME, decision)
        assertTrue(state.progress > 0.45f, "drag progress should follow finger")

        val settled = state.onUp(timeMs = 32)
        assertEquals(1f, settled)
        assertEquals(1f, state.progress)
    }

    @Test
    fun homeTabHorizontalDragFromAwayFromLeftEdgeSettlesOpen() {
        val state = HomeSidePanelGestureState(
            HomeSidePanelGestureConfig(touchSlopPx = 8f)
        )
        state.setSelectedTab(0)
        state.onDown(x = 240f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.CONSUME,
            state.onMove(x = 360f, y = 204f, timeMs = 16)
        )
        assertTrue(state.progress > 0.3f)
    }

    @Test
    fun closedStateLeftwardDragPassesThrough() {
        val state = HomeSidePanelGestureState()
        state.setSelectedTab(0)
        state.onDown(x = 240f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.PASS,
            state.onMove(x = 120f, y = 204f, timeMs = 16)
        )
        assertEquals(0f, state.progress)
    }

    @Test
    fun nonHomeTabIgnoresEdgeDrag() {
        val state = HomeSidePanelGestureState(
            HomeSidePanelGestureConfig(touchSlopPx = 8f)
        )
        state.setSelectedTab(1)
        state.onDown(x = 4f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.PASS,
            state.onMove(x = 220f, y = 204f, timeMs = 16)
        )
        assertEquals(0f, state.progress)
    }

    @Test
    fun verticalDominantDragDoesNotOpen() {
        val state = HomeSidePanelGestureState(
            HomeSidePanelGestureConfig(touchSlopPx = 8f)
        )
        state.setSelectedTab(0)
        state.onDown(x = 4f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.PASS,
            state.onMove(x = 20f, y = 260f, timeMs = 16)
        )
        assertEquals(0f, state.progress)
    }

    @Test
    fun openDrawerCanBeClosedExplicitly() {
        val state = HomeSidePanelGestureState()
        state.setSelectedTab(0)
        state.onDown(x = 0f, y = 0f, widthPx = 400f, timeMs = 0)
        state.onMove(x = 360f, y = 0f, timeMs = 16)
        state.onUp(timeMs = 32)

        assertEquals(0f, state.close())
        assertEquals(0f, state.progress)
    }

    @Test
    fun snapToClampsProgressForProgrammaticAnimation() {
        val state = HomeSidePanelGestureState()

        assertEquals(1f, state.snapTo(2f))
        assertEquals(1f, state.progress)
        assertEquals(0f, state.snapTo(-1f))
        assertEquals(0f, state.progress)
    }

    @Test
    fun alreadyOpenDrawerAcceptsClosingDragAwayFromEdge() {
        val state = HomeSidePanelGestureState()
        state.setSelectedTab(0)
        state.onDown(x = 0f, y = 0f, widthPx = 400f, timeMs = 0)
        state.onMove(x = 360f, y = 0f, timeMs = 16)
        state.onUp(timeMs = 32)

        state.onDown(x = 360f, y = 0f, widthPx = 400f, timeMs = 64)
        assertEquals(
            HomeSidePanelGestureDecision.CONSUME,
            state.onMove(x = 100f, y = 0f, timeMs = 80)
        )
        assertTrue(state.progress < 0.35f, "closing drag should reduce progress")
        assertEquals(0f, state.onUp(timeMs = 96))
    }

    @Test
    fun fullyOpenPanelPassesDownToComposeButKeepsMoveForClosingGesture() {
        assertTrue(homeSidePanelShouldPassFullyOpenTouchToChild(MotionEvent.ACTION_DOWN))
        assertFalse(homeSidePanelShouldPassFullyOpenTouchToChild(MotionEvent.ACTION_MOVE))
        assertTrue(homeSidePanelShouldPassFullyOpenTouchToChild(MotionEvent.ACTION_UP))
        assertTrue(homeSidePanelShouldPassFullyOpenTouchToChild(MotionEvent.ACTION_CANCEL))
    }

    @Test
    fun backIsConsumedWhileDrawerIsOpenOrBeingDragged() {
        assertTrue(homeSidePanelShouldConsumeBack(progress = 1f, dragging = false, tracking = false))
        assertTrue(homeSidePanelShouldConsumeBack(progress = 0f, dragging = true, tracking = false))
        assertTrue(homeSidePanelShouldConsumeBack(progress = 0f, dragging = false, tracking = true))
        assertFalse(homeSidePanelShouldConsumeBack(progress = 0f, dragging = false, tracking = false))
    }

    @Test
    fun moveTaskToBackIsConsumedWhileDrawerIsOpenOrBeingDragged() {
        assertTrue(homeSidePanelShouldConsumeMoveTaskToBack(progress = 1f, dragging = false, tracking = false))
        assertTrue(homeSidePanelShouldConsumeMoveTaskToBack(progress = 0f, dragging = true, tracking = false))
        assertTrue(homeSidePanelShouldConsumeMoveTaskToBack(progress = 0f, dragging = false, tracking = true))
        assertFalse(homeSidePanelShouldConsumeMoveTaskToBack(progress = 0f, dragging = false, tracking = false))
    }

    @Test
    fun launcherEdgeToEdgeWaitsUntilDecorIsAttached() {
        assertTrue(homeSidePanelShouldDeferEdgeToEdgeUntilDecorAttached(isAttached = false))
        assertFalse(homeSidePanelShouldDeferEdgeToEdgeUntilDecorAttached(isAttached = true))
    }

    @Test
    fun edgeToEdgeIsReappliedWhenHomeTabIsSelected() {
        assertTrue(homeSidePanelShouldReapplyEdgeToEdgeAfterTabSelection(0))
        assertFalse(homeSidePanelShouldReapplyEdgeToEdgeAfterTabSelection(1))
    }

    @Test
    fun externalChromeLookupUsesStableViewClassNames() {
        assertTrue(homeSidePanelIsActionBarContainerClass("androidx.appcompat.widget.ActionBarContainer"))
        assertTrue(homeSidePanelIsToolbarClass("androidx.appcompat.widget.Toolbar"))
        assertFalse(homeSidePanelIsActionBarContainerClass("ei"))
        assertFalse(homeSidePanelIsToolbarClass("ef"))
    }

    @Test
    fun actionBarOverlayChildIsNeverReparented() {
        assertFalse(
            homeSidePanelShouldReparentExternalChrome(
                progress = 0.4f,
                isCurrentHost = true,
                isInContentWrapper = false,
                parentClassName = "androidx.appcompat.widget.ActionBarOverlayLayout",
            )
        )
    }

    @Test
    fun reopenedFabIsReparentedWhileDrawerIsOpen() {
        assertTrue(
            homeSidePanelShouldReparentExternalChrome(
                progress = 0.4f,
                isCurrentHost = true,
                isInContentWrapper = false,
                parentClassName = "android.widget.FrameLayout",
            )
        )
        assertFalse(
            homeSidePanelShouldReparentExternalChrome(
                progress = 0f,
                isCurrentHost = true,
                isInContentWrapper = false,
                parentClassName = "android.widget.FrameLayout",
            )
        )
        assertFalse(
            homeSidePanelShouldReparentExternalChrome(
                progress = 0.4f,
                isCurrentHost = true,
                isInContentWrapper = true,
                parentClassName = "android.widget.FrameLayout",
            )
        )
        assertFalse(
            homeSidePanelShouldReparentExternalChrome(
                progress = 0.4f,
                isCurrentHost = false,
                isInContentWrapper = false,
                parentClassName = "android.widget.FrameLayout",
            )
        )
    }

    @Test
    fun actionBarUsesTheSameVisualTransformAsHomeContent() {
        val closed = homeSidePanelVisualTransform(progress = 0f, density = 3f)
        assertEquals(1f, closed.scale)
        assertEquals(0f, closed.translationXPx)
        assertEquals(0f, closed.translationYPx)

        val open = homeSidePanelVisualTransform(progress = 1f, density = 3f)
        assertEquals(0.95f, open.scale)
        assertEquals(21f, open.translationXPx)
        assertEquals(24f, open.translationYPx)
    }
}
