package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HomeSidePanelRequestPoolTest {

    @Test
    fun sameKeySharesOneUnderlyingRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        try {
            val starts = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Int>()
            val first = pool.subscribe("same") {
                starts.incrementAndGet()
                started.complete(Unit)
                result.await()
            }
            val second = pool.subscribe("same") {
                error("A shared key must not start a second request")
            }

            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            result.complete(26)

            assertEquals(26, first.await())
            assertEquals(26, second.await())
            assertEquals(1, starts.get())
        } finally {
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun releasingOneSubscriberKeepsRequestAliveForTheOther() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        try {
            val started = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Int>()
            val first = pool.subscribe("same") {
                started.complete(Unit)
                try {
                    result.await()
                } catch (error: CancellationException) {
                    canceled.complete(Unit)
                    throw error
                }
            }
            val second = pool.subscribe("same") { error("duplicate request") }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }
            val secondWaiter = async(start = CoroutineStart.UNDISPATCHED) { second.await() }

            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            firstWaiter.cancelAndJoin()
            assertFalse(canceled.isCompleted)

            result.complete(42)
            assertEquals(42, secondWaiter.await())
        } finally {
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun releasingTheLastSubscriberCancelsTheUnderlyingRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        try {
            val started = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Int>()
            val first = pool.subscribe("same") {
                started.complete(Unit)
                try {
                    never.await()
                } catch (error: CancellationException) {
                    canceled.complete(Unit)
                    throw error
                }
            }
            val second = pool.subscribe("same") { error("duplicate request") }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }
            val secondWaiter = async(start = CoroutineStart.UNDISPATCHED) { second.await() }

            withTimeout(TEST_TIMEOUT_MS) { started.await() }
            firstWaiter.cancelAndJoin()
            assertFalse(canceled.isCompleted)
            secondWaiter.cancelAndJoin()

            withTimeout(TEST_TIMEOUT_MS) { canceled.await() }
        } finally {
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun immediateSameKeyResubscribeWaitsForRetiredRequestCleanup() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val admittedBeforeCleanup = CompletableDeferred<Unit>()
        val runningRequests = AtomicInteger()
        var replacementWaiter: Deferred<Int>? = null
        try {
            val first = pool.subscribe("same") {
                runningRequests.incrementAndGet()
                firstStarted.complete(Unit)
                try {
                    CompletableDeferred<Int>().await()
                } finally {
                    cleanupStarted.complete(Unit)
                    withContext(NonCancellable) {
                        allowCleanup.await()
                    }
                    runningRequests.decrementAndGet()
                    cleanupFinished.complete(Unit)
                }
            }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }

            withTimeout(TEST_TIMEOUT_MS) { firstStarted.await() }
            firstWaiter.cancelAndJoin()
            withTimeout(TEST_TIMEOUT_MS) { cleanupStarted.await() }

            replacementWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                pool.await("same") {
                    if (!cleanupFinished.isCompleted) admittedBeforeCleanup.complete(Unit)
                    runningRequests.incrementAndGet()
                    replacementStarted.complete(Unit)
                    try {
                        2
                    } finally {
                        runningRequests.decrementAndGet()
                    }
                }
            }

            assertFalse(replacementStarted.isCompleted)
            assertFalse(admittedBeforeCleanup.isCompleted)
            assertEquals(1, runningRequests.get())

            allowCleanup.complete(Unit)
            assertEquals(2, withTimeout(TEST_TIMEOUT_MS) { replacementWaiter.await() })
            assertFalse(admittedBeforeCleanup.isCompleted)
            assertEquals(0, runningRequests.get())
        } finally {
            allowCleanup.complete(Unit)
            replacementWaiter?.cancelAndJoin()
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun closeUnblocksRetirementWaiterWithoutAdmittingReplacement() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        try {
            val first = pool.subscribe("same") {
                firstStarted.complete(Unit)
                try {
                    CompletableDeferred<Int>().await()
                } finally {
                    cleanupStarted.complete(Unit)
                    withContext(NonCancellable) { allowCleanup.await() }
                }
            }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }

            withTimeout(TEST_TIMEOUT_MS) { firstStarted.await() }
            firstWaiter.cancelAndJoin()
            withTimeout(TEST_TIMEOUT_MS) { cleanupStarted.await() }

            supervisorScope {
                val replacementWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                    pool.await("same") {
                        replacementStarted.complete(Unit)
                        2
                    }
                }
                try {
                    assertFalse(replacementStarted.isCompleted)
                    pool.close()

                    val failure = runCatching {
                        withTimeout(TEST_TIMEOUT_MS) { replacementWaiter.await() }
                    }.exceptionOrNull()
                    assertInstanceOf(IllegalStateException::class.java, failure)
                    assertFalse(replacementStarted.isCompleted)
                } finally {
                    replacementWaiter.cancelAndJoin()
                }
            }
        } finally {
            allowCleanup.complete(Unit)
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun differentKeysRunConcurrently() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        try {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstResult = CompletableDeferred<Int>()
            val secondResult = CompletableDeferred<Int>()
            val first = pool.subscribe("first") {
                firstStarted.complete(Unit)
                firstResult.await()
            }
            val second = pool.subscribe("second") {
                secondStarted.complete(Unit)
                secondResult.await()
            }

            withTimeout(TEST_TIMEOUT_MS) {
                firstStarted.await()
                secondStarted.await()
            }
            firstResult.complete(1)
            secondResult.complete(2)

            assertEquals(1, first.await())
            assertEquals(2, second.await())
        } finally {
            pool.close()
            owner.cancel()
        }
    }

    @Test
    fun closeCancelsEveryUnderlyingRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = HomeSidePanelRequestPool<String, Int>(owner)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstCanceled = CompletableDeferred<Unit>()
        val secondCanceled = CompletableDeferred<Unit>()
        val first = pool.subscribe("first") {
            firstStarted.complete(Unit)
            awaitCancellation(firstCanceled)
        }
        val second = pool.subscribe("second") {
            secondStarted.complete(Unit)
            awaitCancellation(secondCanceled)
        }
        val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { first.await() }
        val secondWaiter = async(start = CoroutineStart.UNDISPATCHED) { second.await() }

        withTimeout(TEST_TIMEOUT_MS) {
            firstStarted.await()
            secondStarted.await()
        }
        pool.close()

        withTimeout(TEST_TIMEOUT_MS) {
            firstCanceled.await()
            secondCanceled.await()
        }
        firstWaiter.cancelAndJoin()
        secondWaiter.cancelAndJoin()
        owner.cancel()
    }

    private suspend fun awaitCancellation(canceled: CompletableDeferred<Unit>): Int {
        return try {
            CompletableDeferred<Int>().await()
        } catch (error: CancellationException) {
            canceled.complete(Unit)
            throw error
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
