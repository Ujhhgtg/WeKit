package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DexResolutionDiagnosticTest {

    @Test
    fun explicitExpectedPlaceholderDoesNotFail() {
        val delegate = DexMethodDelegate("Feature:method")
        delegate.resetForDexTest()

        delegate.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "not present in this host branch",
        )

        assertEquals(DexResolutionStatus.EXPECTED_FAILURE, delegate.diagnostic.status)
    }

    @Test
    fun unclassifiedPlaceholderIsUnexpectedFailure() {
        val delegate = DexMethodDelegate("Feature:method")
        delegate.resetForDexTest()

        delegate.setPlaceholderDescriptor()

        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, delegate.diagnostic.status)
    }

    @Test
    fun pendingDelegateBecomesBlockedAfterSiblingThrows() {
        val delegate = DexClassDelegate("Feature:later")
        delegate.resetForDexTest()

        delegate.markBlocked("Feature:failing")

        assertEquals(DexResolutionStatus.BLOCKED, delegate.diagnostic.status)
    }

    @Test
    fun normalCompletionTurnsPendingIntoIncomplete() {
        val delegate = DexFieldDelegate("Feature:field")
        delegate.resetForDexTest()

        delegate.markIncomplete()

        assertEquals(DexResolutionStatus.INCOMPLETE, delegate.diagnostic.status)
    }
}
