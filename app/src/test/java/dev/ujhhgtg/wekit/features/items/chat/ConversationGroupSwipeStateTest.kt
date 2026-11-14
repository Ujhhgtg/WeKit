package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.items.chat.ConversationGroupSwipeState.Direction
import dev.ujhhgtg.wekit.features.items.chat.ConversationGroupSwipeState.Owner
import dev.ujhhgtg.wekit.features.items.chat.ConversationGroupSwipeState.Settlement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationGroupSwipeStateTest {
    @Test
    fun horizontalIntentWaitsForChildDispatchBeforeTheGroupCanOwnIt() {
        val state = startedState()

        assertNull(state.onMove(x = 197f, y = 202f, timeMs = 10))
        assertFalse(state.claimGroup())
        assertNull(state.onMove(x = 188f, y = 211f, timeMs = 20))
        assertEquals(Owner.PENDING, state.owner)
        assertNull(state.candidateDirection)

        assertEquals(Direction.NEXT, state.onMove(x = 120f, y = 211f, timeMs = 100))
        assertEquals(Owner.PENDING, state.owner)
        assertEquals(0.2f, state.progress, 0.0001f)
        assertTrue(state.claimGroup())
        assertEquals(Owner.GROUP, state.owner)
        assertFalse(state.claimGroup())
    }

    @Test
    fun childClaimDuringTheDecidingMoveWinsForTheWholeStream() {
        val state = startedState()
        state.onMove(x = 170f, y = 200f, timeMs = 16)
        state.onChildClaimed()

        assertEquals(Owner.CHILD, state.owner)
        assertFalse(state.claimGroup())
        assertNull(state.onMove(x = 360f, y = 200f, timeMs = 32))
        assertNull(state.candidateDirection)
        assertNull(state.onUp(timeMs = 48))

        state.onDown(200f, 200f, 400f, 100, canGoPrevious = true, canGoNext = true)
        state.onMove(x = 230f, y = 200f, timeMs = 116)
        assertTrue(state.claimGroup())
        assertEquals(Direction.PREVIOUS, state.candidateDirection)
    }

    @Test
    fun boundaryHandoffCannotBeRetakenAfterTheFingerReverses() {
        val state = startedState(canGoPrevious = false)
        // Tiny inward motion must not permanently take the stream from the outer pager.
        state.onMove(x = 198f, y = 200f, timeMs = 10)
        state.onMove(x = 225f, y = 200f, timeMs = 30)

        assertEquals(Owner.OUTER, state.owner)
        assertNull(state.onMove(x = 80f, y = 200f, timeMs = 100))
        assertEquals(Owner.OUTER, state.owner)
        assertFalse(state.claimGroup())
        assertNull(state.onUp(timeMs = 120))
    }

    @Test
    fun verticalScrollingCannotTurnIntoAGroupSwipeMidGesture() {
        val state = startedState()
        state.onMove(x = 208f, y = 220f, timeMs = 16)

        assertEquals(Owner.NATIVE_VERTICAL, state.owner)
        assertNull(state.onMove(x = 50f, y = 220f, timeMs = 32))
        assertFalse(state.claimGroup())
        assertNull(state.onCancel())
    }

    @Test
    fun ineligibleGestureNeverProducesAGroupCandidate() {
        val state = ConversationGroupSwipeState(touchSlopPx = 8f)
        state.onDown(200f, 200f, 400f, 0, true, true, eligible = false)

        assertNull(state.onMove(x = 0f, y = 200f, timeMs = 16))
        assertEquals(Owner.OUTER, state.owner)
        assertFalse(state.claimGroup())
        assertNull(state.onUp(timeMs = 32))
    }

    @Test
    fun acquiredGroupCannotBeStolenByLaterChildClaimsOrReversePastItsStart() {
        val state = startedState()
        state.onMove(x = 280f, y = 200f, timeMs = 100)
        assertTrue(state.claimGroup())
        state.onChildClaimed()
        state.onMove(x = 120f, y = 200f, timeMs = 200)

        assertEquals(Owner.GROUP, state.owner)
        assertEquals(Direction.PREVIOUS, state.candidateDirection)
        assertEquals(0f, state.progress)
        assertEquals(Settlement(Direction.PREVIOUS, commit = false), state.onUp(timeMs = 210))
    }

    @Test
    fun cancelledSwipeAlwaysRollsBackEvenAfterPassingTheCommitDistance() {
        val state = startedState()
        state.onMove(x = -120f, y = 200f, timeMs = 16)
        state.claimGroup()

        assertEquals(Settlement(Direction.NEXT, commit = false), state.onCancel())
        assertEquals(Owner.IDLE, state.owner)
        assertNull(state.candidateDirection)
        assertEquals(0f, state.progress)
        assertNull(state.onUp(timeMs = 32))
    }

    @Test
    fun aLongSlowDragCommitsOnlyOneNeighborEvenWhenDraggedBeyondOnePage() {
        val state = startedState()
        state.onMove(x = -800f, y = 200f, timeMs = 2000)
        state.claimGroup()

        assertEquals(1f, state.progress)
        assertEquals(Settlement(Direction.NEXT, commit = true), state.onUp(timeMs = 3000))
        assertEquals(Owner.IDLE, state.owner)
        assertNull(state.onMove(x = -1200f, y = 200f, timeMs = 3100))
        assertNull(state.onUp(timeMs = 3200))
    }

    @Test
    fun shortSlowDragRollsBackButShortFlickCommits() {
        val slow = startedState()
        slow.onMove(x = 240f, y = 200f, timeMs = 500)
        slow.claimGroup()
        assertEquals(Settlement(Direction.PREVIOUS, commit = false), slow.onUp(timeMs = 516))

        val fast = startedState()
        fast.onMove(x = 160f, y = 200f, timeMs = 16)
        fast.claimGroup()
        assertEquals(Settlement(Direction.NEXT, commit = true), fast.onUp(timeMs = 32))
    }

    @Test
    fun releasingAfterHoldingStillDoesNotUseTheOldFlickVelocity() {
        val state = startedState()
        state.onMove(x = 160f, y = 200f, timeMs = 16)
        state.claimGroup()

        assertEquals(Settlement(Direction.NEXT, commit = false), state.onUp(timeMs = 500))
    }

    @Test
    fun reversalUsesItsOwnVelocityInsteadOfTheInitialFlick() {
        val state = startedState()
        state.onMove(x = 120f, y = 200f, timeMs = 16)
        state.claimGroup()
        state.onMove(x = 160f, y = 200f, timeMs = 32)

        assertEquals(Settlement(Direction.NEXT, commit = false), state.onUp(timeMs = 48))
    }

    @Test
    fun observingTheSameMoveTwicePreservesItsVelocity() {
        val state = startedState()
        state.onMove(x = 160f, y = 200f, timeMs = 16)
        state.onMove(x = 160f, y = 200f, timeMs = 16)
        state.claimGroup()

        assertEquals(Settlement(Direction.NEXT, commit = true), state.onUp(timeMs = 32))
    }

    private fun startedState(canGoPrevious: Boolean = true): ConversationGroupSwipeState =
        ConversationGroupSwipeState(touchSlopPx = 8f).apply {
            onDown(200f, 200f, 400f, 0, canGoPrevious = canGoPrevious, canGoNext = true)
        }
}
