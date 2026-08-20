package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelDragStateTest {

    private class CountingIds : HomeSidePanelIdGenerator {
        var count = 0
            private set

        override fun nextId(): String = "drag-id-${count++}"
    }

    @Test
    fun variableHeightBoundsChooseInsertionByCenters() {
        val state = HomeSidePanelDragState()
        state.registerCardBounds("a", 0, RootDragBounds(0f, 0f, 300f, 100f))
        state.registerCardBounds("b", 1, RootDragBounds(0f, 110f, 300f, 310f))
        state.registerCardBounds("c", 2, RootDragBounds(0f, 320f, 300f, 400f))
        state.begin(
            payload = HomeSidePanelDragPayload.NewCard(HomeSidePanelCardType.WEATHER),
            pointerId = 1L,
            rootPosition = RootDragPosition(150f, 20f),
        )

        assertEquals(HomeSidePanelDragTarget.Card(0), state.snapshot!!.target)
        state.updateRootPosition(150f, 180f)
        assertEquals(HomeSidePanelDragTarget.Card(1), state.snapshot!!.target)
        state.updateRootPosition(150f, 430f)
        assertEquals(HomeSidePanelDragTarget.Card(3), state.snapshot!!.target)
    }

    @Test
    fun initiallyConsumedReleaseCancelsWhileUnconsumedReleaseFinishes() {
        assertEquals(
            HomeSidePanelPointerLifecycleDecision.Cancel,
            homeSidePanelPointerLifecycleDecision(
                previousPressed = true,
                pressed = false,
                consumedAtInitialPass = true,
            ),
        )
        assertEquals(
            HomeSidePanelPointerLifecycleDecision.Finish,
            homeSidePanelPointerLifecycleDecision(
                previousPressed = true,
                pressed = false,
                consumedAtInitialPass = false,
            ),
        )
        assertEquals(
            HomeSidePanelPointerLifecycleDecision.Continue,
            homeSidePanelPointerLifecycleDecision(
                previousPressed = true,
                pressed = true,
                consumedAtInitialPass = true,
            ),
        )
    }

    @Test
    fun horizontalActionTargetUsesThePointersRowBeforeItsXCenter() {
        val state = horizontalTwoRowState()
        state.begin(
            payload = HomeSidePanelDragPayload.NewAction(
                "target",
                HomeSidePanelActionKind.SCAN,
            ),
            pointerId = 2L,
            rootPosition = RootDragPosition(120f, 150f),
        )

        assertEquals(
            HomeSidePanelDragTarget.Action("target", 4),
            state.snapshot!!.target,
        )
    }

    @Test
    fun horizontalActionTargetChoosesNearestRowBetweenRowsAndAtEdges() {
        val state = horizontalTwoRowState()
        state.begin(
            payload = HomeSidePanelDragPayload.NewAction(
                "target",
                HomeSidePanelActionKind.SCAN,
            ),
            pointerId = 3L,
            rootPosition = RootDragPosition(120f, 85f),
        )

        assertEquals(HomeSidePanelDragTarget.Action("target", 1), state.snapshot!!.target)
        state.updateRootPosition(120f, 95f)
        assertEquals(HomeSidePanelDragTarget.Action("target", 4), state.snapshot!!.target)
        state.updateRootPosition(0f, -20f)
        assertEquals(HomeSidePanelDragTarget.Action("target", 0), state.snapshot!!.target)
        state.updateRootPosition(300f, 220f)
        assertEquals(HomeSidePanelDragTarget.Action("target", 6), state.snapshot!!.target)
    }

    @Test
    fun existingMoveNormalizesAfterRemoval() {
        assertEquals(2, normalizedMoveDestination(0, 3))
        assertEquals(0, normalizedMoveDestination(2, 0))
    }

    @Test
    fun cancelledExternalCandidateDoesNotCommit() {
        val state = HomeSidePanelDragState()
        state.begin(
            payload = HomeSidePanelDragPayload.NewCard(HomeSidePanelCardType.WEATHER),
            pointerId = 7L,
        )

        state.cancel()

        assertNull(state.finish())
    }

    @Test
    fun finishCommitsTheCurrentSlotExactlyOnce() {
        val state = HomeSidePanelDragState()
        state.registerCardBounds("first", 0, RootDragBounds(0f, 0f, 300f, 100f))
        state.registerCardBounds("second", 1, RootDragBounds(0f, 110f, 300f, 260f))
        state.begin(
            payload = HomeSidePanelDragPayload.NewCard(HomeSidePanelCardType.HITOKOTO),
            pointerId = 9L,
            rootPosition = RootDragPosition(150f, 240f),
        )

        assertEquals(
            HomeSidePanelDragCommit.InsertCard(HomeSidePanelCardType.HITOKOTO, 2),
            state.finish(),
        )
        assertNull(state.finish())
    }

    @Test
    fun emptyPageViewportAcceptsTheFirstCard() {
        val state = HomeSidePanelDragState()
        state.registerViewport(RootDragBounds(0f, 0f, 300f, 600f))

        state.begin(
            payload = HomeSidePanelDragPayload.NewCard(HomeSidePanelCardType.DATE_TIME),
            pointerId = 10L,
            rootPosition = RootDragPosition(150f, 300f),
        )

        assertEquals(
            HomeSidePanelDragCommit.InsertCard(HomeSidePanelCardType.DATE_TIME, 0),
            state.finish(),
        )
    }

    @Test
    fun newIdIsAllocatedOnlyWhenSuccessfulCommitIsApplied() {
        val ids = CountingIds()
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(cards = emptyList<HomeSidePanelCardConfig>()),
            ids,
        )
        val state = HomeSidePanelDragState()
        state.registerViewport(RootDragBounds(0f, 0f, 300f, 600f))
        state.begin(
            payload = HomeSidePanelDragPayload.NewCard(HomeSidePanelCardType.WALLET),
            pointerId = 12L,
            rootPosition = RootDragPosition(150f, 300f),
        )

        assertEquals(0, ids.count)
        val commit = state.finish()!!
        assertEquals(0, ids.count)

        editor.applyHomeSidePanelDragCommit(commit)

        assertEquals(1, ids.count)
        assertEquals("drag-id-0", editor.draft.cards.single().id)
    }

    @Test
    fun actionInsertionIsScopedToItsTargetCard() {
        val state = HomeSidePanelDragState()
        state.registerActionContainer(
            cardId = "target",
            axis = HomeSidePanelDragAxis.Horizontal,
            bounds = RootDragBounds(0f, 0f, 300f, 100f),
        )
        state.registerActionBounds(
            cardId = "target",
            actionId = "a",
            index = 0,
            bounds = RootDragBounds(0f, 0f, 80f, 100f),
        )
        state.registerActionBounds(
            cardId = "target",
            actionId = "b",
            index = 1,
            bounds = RootDragBounds(90f, 0f, 180f, 100f),
        )
        state.registerActionContainer(
            cardId = "other",
            axis = HomeSidePanelDragAxis.Horizontal,
            bounds = RootDragBounds(0f, 120f, 300f, 220f),
        )
        state.registerActionBounds(
            cardId = "other",
            actionId = "foreign",
            index = 0,
            bounds = RootDragBounds(0f, 120f, 80f, 220f),
        )
        state.begin(
            payload = HomeSidePanelDragPayload.NewAction(
                "target",
                HomeSidePanelActionKind.SCAN,
            ),
            pointerId = 11L,
            rootPosition = RootDragPosition(100f, 50f),
        )

        assertEquals(
            HomeSidePanelDragTarget.Action("target", 1),
            state.snapshot!!.target,
        )

        state.updateRootPosition(40f, 170f)

        assertNull(state.snapshot!!.target)
        assertNull(state.finish())
    }

    @Test
    fun virtualAddSelectsWholeCardPayload() {
        assertEquals(
            HomeSidePanelDragPayload.ExistingCard("card"),
            homeSidePanelExistingDragPayload(
                cardId = "card",
                source = HomeSidePanelExistingDragSource.VirtualAdd,
            ),
        )
        assertEquals(
            HomeSidePanelDragPayload.ExistingAction("card", "action"),
            homeSidePanelExistingDragPayload(
                cardId = "card",
                source = HomeSidePanelExistingDragSource.Action("action"),
            ),
        )
    }

    @Test
    fun realActionClaimWinsOverItsCardBackground() {
        val state = HomeSidePanelDragState()
        val card = HomeSidePanelDragPayload.ExistingCard("card")
        val action = HomeSidePanelDragPayload.ExistingAction("card", "action")

        state.claimSource(13L, card)
        state.claimSource(13L, action)

        assertFalse(state.begin(card, 13L))
        assertTrue(state.begin(action, 13L))
        assertEquals(action, state.snapshot!!.payload)
    }

    private fun horizontalTwoRowState(): HomeSidePanelDragState =
        HomeSidePanelDragState().apply {
            registerActionContainer(
                cardId = "target",
                axis = HomeSidePanelDragAxis.Horizontal,
                bounds = RootDragBounds(0f, -40f, 300f, 240f),
            )
            listOf(
                RootDragBounds(0f, 0f, 80f, 80f),
                RootDragBounds(90f, 0f, 170f, 80f),
                RootDragBounds(180f, 0f, 260f, 80f),
                RootDragBounds(0f, 100f, 80f, 180f),
                RootDragBounds(90f, 100f, 170f, 180f),
                RootDragBounds(180f, 100f, 260f, 180f),
            ).forEachIndexed { index, bounds ->
                registerActionBounds("target", "action-$index", index, bounds)
            }
        }
}
