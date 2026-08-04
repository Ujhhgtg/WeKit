package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelGestureStateTest {

    @Test
    fun horizontalDragFromAnywhereOnHomeTabOpensPanel() {
        val state = HomeSidePanelGestureState(HomeSidePanelGestureConfig(touchSlopPx = 8f))
        state.setSelectedTab(0)
        state.onDown(x = 240f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.CONSUME,
            state.onMove(x = 360f, y = 204f, timeMs = 16),
        )
        assertEquals(1f, state.onUp(timeMs = 32))
    }

    @Test
    fun verticalOrLeftwardDragDoesNotOpenPanel() {
        val vertical = HomeSidePanelGestureState(HomeSidePanelGestureConfig(touchSlopPx = 8f))
        vertical.setSelectedTab(0)
        vertical.onDown(x = 40f, y = 200f, widthPx = 400f, timeMs = 0)
        assertEquals(
            HomeSidePanelGestureDecision.PASS,
            vertical.onMove(x = 55f, y = 280f, timeMs = 16),
        )

        val leftward = HomeSidePanelGestureState(HomeSidePanelGestureConfig(touchSlopPx = 8f))
        leftward.setSelectedTab(0)
        leftward.onDown(x = 240f, y = 200f, widthPx = 400f, timeMs = 0)
        assertEquals(
            HomeSidePanelGestureDecision.PASS,
            leftward.onMove(x = 100f, y = 204f, timeMs = 16),
        )
    }

    @Test
    fun openPanelCanBeClosedByDraggingItsContent() {
        val state = HomeSidePanelGestureState(HomeSidePanelGestureConfig(touchSlopPx = 8f))
        state.setSelectedTab(0)
        state.snapTo(1f)
        state.onDown(x = 360f, y = 200f, widthPx = 400f, timeMs = 0)

        assertEquals(
            HomeSidePanelGestureDecision.CONSUME,
            state.onMove(x = 100f, y = 204f, timeMs = 16),
        )
        assertTrue(state.progress < 0.35f)
        assertEquals(0f, state.onUp(timeMs = 32))
    }
}
