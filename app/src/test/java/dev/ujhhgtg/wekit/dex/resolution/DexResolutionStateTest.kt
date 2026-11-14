package dev.ujhhgtg.wekit.dex.resolution

import dev.ujhhgtg.wekit.dex.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dex.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.dex.dsl.DexMethodDelegate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexResolutionStateTest {
    @Test
    fun expectedFailureIsSettledButNotFound() {
        val delegate = DexMethodDelegate("method")

        delegate.markExpectedFailure()

        assertEquals(DexResolutionState.ExpectedFailure, delegate.state)
        assertFalse(delegate.isFound)
        assertThrows(IllegalStateException::class.java) { delegate.requireDescriptor() }
    }

    @Test
    fun unresolvedDelegateIsNeitherFoundNorAbsent() {
        val delegate = DexMethodDelegate("method")

        assertThrows(IllegalStateException::class.java) { delegate.isFound }
    }

    @Test
    fun cachedNullDescriptorRestoresExpectedFailure() {
        val delegate = DexFieldDelegate("field")

        delegate.restore(null)
        assertEquals(DexResolutionState.ExpectedFailure, delegate.state)

        delegate.restore("La;->b:I")
        assertEquals(DexResolutionState.Success("La;->b:I"), delegate.state)
    }

    @Test
    fun pendingDelegateBecomesBlockedAfterSiblingThrows() {
        val delegate = DexClassDelegate("later")

        delegate.markBlocked("failing")

        assertEquals(DexResolutionState.Blocked("failing"), delegate.state)
    }

    @Test
    fun normalCompletionTurnsPendingIntoIncomplete() {
        val delegate = DexFieldDelegate("field")

        delegate.markIncomplete()

        assertEquals(DexResolutionState.Incomplete, delegate.state)
    }
}
