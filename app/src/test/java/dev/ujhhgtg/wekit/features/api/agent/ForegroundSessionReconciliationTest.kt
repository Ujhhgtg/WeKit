package dev.ujhhgtg.wekit.features.api.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForegroundSessionReconciliationTest {
    @Test
    fun `switch during message reload cannot publish the old foreground`() = runBlocking {
        var currentSessionId: String? = "old"
        val oldLoadStarted = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        val applied = mutableListOf<String>()
        val published = mutableListOf<String>()

        val reconciliation = async {
            reconcileForegroundSession(
                currentSessionId = { currentSessionId },
                loadState = { "state:$it" },
                applyStateIfCurrent = { sessionId, _ ->
                    if (currentSessionId != sessionId) false else true.also { applied += sessionId }
                },
                loadMessages = { sessionId ->
                    if (sessionId == "old") {
                        oldLoadStarted.complete(Unit)
                        releaseOldLoad.await()
                    }
                    "messages:$sessionId"
                },
                publishMessagesIfCurrent = { sessionId, _ ->
                    if (currentSessionId != sessionId) false else true.also { published += sessionId }
                },
            )
        }

        oldLoadStarted.await()
        currentSessionId = "new"
        releaseOldLoad.complete(Unit)
        reconciliation.await()

        assertEquals(listOf("old", "new"), applied)
        assertEquals(listOf("new"), published)
    }

    @Test
    fun `switch during missing state load recomputes the new foreground`() = runBlocking {
        var currentSessionId: String? = "old"
        val applied = mutableListOf<String>()

        reconcileForegroundSession(
            currentSessionId = { currentSessionId },
            loadState = { sessionId ->
                if (sessionId == "old") {
                    currentSessionId = "new"
                    null
                } else {
                    "state:$sessionId"
                }
            },
            applyStateIfCurrent = { sessionId, _ -> true.also { applied += sessionId } },
            loadMessages = { "messages:$it" },
            publishMessagesIfCurrent = { _, _ -> true },
        )

        assertEquals(listOf("new"), applied)
    }
}
