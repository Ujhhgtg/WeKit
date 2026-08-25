package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DexResolutionDiagnosticTest {

    private val owner = DiagnosticTestOwner()

    @Test
    fun explicitExpectedPlaceholderDoesNotFail() {
        val delegate = DexMethodDelegate(owner, "method")
        delegate.resetForDexTest()

        delegate.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "not present in this host branch",
        )

        assertEquals(DexResolutionStatus.EXPECTED_FAILURE, delegate.diagnostic.status)
    }

    @Test
    fun unclassifiedPlaceholderIsUnexpectedFailure() {
        val delegate = DexMethodDelegate(owner, "method")
        delegate.resetForDexTest()

        delegate.setPlaceholderDescriptor()

        assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, delegate.diagnostic.status)
    }

    @Test
    fun pendingDelegateBecomesBlockedAfterSiblingThrows() {
        val delegate = DexClassDelegate(owner, "later")
        delegate.resetForDexTest()

        delegate.markBlocked("Feature:failing")

        assertEquals(DexResolutionStatus.BLOCKED, delegate.diagnostic.status)
    }

    @Test
    fun normalCompletionTurnsPendingIntoIncomplete() {
        val delegate = DexFieldDelegate(owner, "field")
        delegate.resetForDexTest()

        delegate.markIncomplete()

        assertEquals(DexResolutionStatus.INCOMPLETE, delegate.diagnostic.status)
    }
}

private class DiagnosticTestOwner : BaseFeature(), IResolveDex {
    override val technicalId = "diagnostic-test"
    override val nameRes = 0
    override val categoryIds = emptyList<String>()
}
