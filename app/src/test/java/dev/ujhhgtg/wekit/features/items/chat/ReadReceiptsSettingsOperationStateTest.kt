package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReceiptsSettingsOperationStateTest {
    @Test
    fun `operation remains owned until its retained owner completes`() {
        val state = SettingsOperationState()
        val owner = checkNotNull(state.begin(ActiveOperation.CONNECTING))

        assertEquals(ActiveOperation.CONNECTING, state.activeOperation)
        assertNull(state.begin(ActiveOperation.DISCONNECTING))

        owner.transition(ActiveOperation.COMMITTING)
        assertEquals(ActiveOperation.COMMITTING, state.activeOperation)

        val feedback = OperationFeedback("connected", FeedbackSeverity.SUCCESS)
        owner.complete(feedback)
        assertNull(state.activeOperation)
        assertEquals(feedback, state.feedback)
    }

    @Test
    fun `disposed screen owner cannot clear a newer operation`() {
        val state = SettingsOperationState()
        val oldOwner = checkNotNull(state.begin(ActiveOperation.REFRESHING))
        oldOwner.complete(OperationFeedback("refreshed", FeedbackSeverity.SUCCESS))
        val currentOwner = checkNotNull(state.begin(ActiveOperation.LOGGING_OUT))

        oldOwner.complete(OperationFeedback("stale", FeedbackSeverity.ERROR))

        assertEquals(ActiveOperation.LOGGING_OUT, state.activeOperation)
        assertEquals("refreshed", state.feedback.message)
        currentOwner.complete(OperationFeedback("logged out", FeedbackSeverity.SUCCESS))
        assertNull(state.activeOperation)
    }

    @Test
    fun `process recreation recovery follows only authoritative transitional state`() {
        val state = SettingsOperationState()

        state.recover(ActiveOperation.RECONNECTING)
        assertEquals(ActiveOperation.RECONNECTING, state.activeOperation)
        assertNull(state.begin(ActiveOperation.CONNECTING))

        state.recover(null)
        assertNull(state.activeOperation)
        assertTrue(state.begin(ActiveOperation.CONNECTING) != null)
    }
}
