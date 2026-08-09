package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReadReceiptsTunnelCoordinationTest {

    @Test
    fun `network invalidation keeps an existing native owner unverifiable until it restarts`() {
        val lease = TunnelNativeLease()
        val credentialWrites = AtomicInteger()
        val pendingTokenClears = AtomicInteger()
        val connectedPublishes = AtomicInteger()

        assertTrue(lease.advance(30))
        assertTrue(lease.activateRequest(30))
        assertTrue(lease.startIfCurrent(30) { true })
        val verification = lease.captureVerification(30)!!

        assertEquals(30, lease.invalidateNetwork())
        assertNull(lease.captureVerification(30))
        assertEquals(
            TunnelVerificationCommit.STALE,
            lease.commitVerification(
                verification,
                writeCredential = { credentialWrites.incrementAndGet(); true },
                clearPendingToken = { pendingTokenClears.incrementAndGet() },
                publishConnected = { connectedPublishes.incrementAndGet() },
            ),
        )
        assertEquals(0, credentialWrites.get())
        assertEquals(0, pendingTokenClears.get())
        assertEquals(0, connectedPublishes.get())

        assertTrue(lease.stopIfOwner(30) {})
        assertNull(lease.captureVerification(30))
        assertTrue(lease.startIfCurrent(30) { true })
        assertEquals(
            TunnelVerificationCommit.COMMITTED,
            lease.commitVerification(
                lease.captureVerification(30)!!,
                writeCredential = { credentialWrites.incrementAndGet(); true },
                clearPendingToken = { pendingTokenClears.incrementAndGet() },
                publishConnected = { connectedPublishes.incrementAndGet() },
            ),
        )
        assertEquals(1, credentialWrites.get())
        assertEquals(1, pendingTokenClears.get())
        assertEquals(1, connectedPublishes.get())
    }

    @Test
    fun `available lost and replacement network events make verification unavailable`() {
        listOf("available", "lost", "replacement").forEachIndexed { index, event ->
            val lease = TunnelNativeLease()
            val credentialWrites = AtomicInteger()
            val pendingTokenClears = AtomicInteger()
            val connectedPublishes = AtomicInteger()
            val generation = (40 + index).toLong()

            assertTrue(lease.advance(generation), event)
            assertTrue(lease.activateRequest(generation), event)
            assertTrue(lease.startIfCurrent(generation) { true }, event)
            val verification = lease.captureVerification(generation)!!

            assertEquals(generation, lease.invalidateNetwork(), event)
            assertNull(lease.captureVerification(generation), event)
            assertEquals(
                TunnelVerificationCommit.STALE,
                lease.commitVerification(
                    verification,
                    writeCredential = { credentialWrites.incrementAndGet(); true },
                    clearPendingToken = { pendingTokenClears.incrementAndGet() },
                    publishConnected = { connectedPublishes.incrementAndGet() },
                ),
                event,
            )
            assertEquals(0, credentialWrites.get(), event)
            assertEquals(0, pendingTokenClears.get(), event)
            assertEquals(0, connectedPublishes.get(), event)
        }
    }

    @Test
    fun `preserving activation keeps a valid session but cannot restore an invalidated one`() {
        val lease = TunnelNativeLease()

        assertTrue(lease.advance(50))
        assertTrue(lease.activateRequest(50))
        assertTrue(lease.startIfCurrent(50) { true })
        assertTrue(lease.advance(51))
        assertTrue(lease.activateRequest(51, preserveNativeSession = true))
        assertTrue(lease.captureVerification(51) != null)

        assertEquals(51, lease.invalidateNetwork())
        assertTrue(lease.activateRequest(51, preserveNativeSession = true))
        assertNull(lease.captureVerification(51))
    }

    @Test
    fun `network invalidation while health is blocked prevents verified side effects`() {
        val lease = TunnelNativeLease()
        val healthStarted = CountDownLatch(1)
        val finishHealth = CountDownLatch(1)
        val credentialWrites = AtomicInteger()
        val pendingTokenClears = AtomicInteger()
        val connectedPublishes = AtomicInteger()
        val commit = AtomicReference<TunnelVerificationCommit>()

        assertTrue(lease.advance(20))
        assertTrue(lease.activateRequest(20))
        assertTrue(lease.startIfCurrent(20) { true })
        val verification = lease.captureVerification(20)!!
        val health = thread {
            healthStarted.countDown()
            finishHealth.await()
            commit.set(
                lease.commitVerification(
                    verification,
                    writeCredential = { credentialWrites.incrementAndGet(); true },
                    clearPendingToken = { pendingTokenClears.incrementAndGet() },
                    publishConnected = { connectedPublishes.incrementAndGet() },
                ),
            )
        }

        healthStarted.await()
        assertEquals(20, lease.invalidateNetwork())
        finishHealth.countDown()
        health.join()

        assertEquals(TunnelVerificationCommit.STALE, commit.get())
        assertEquals(0, credentialWrites.get())
        assertEquals(0, pendingTokenClears.get())
        assertEquals(0, connectedPublishes.get())
    }

    @Test
    fun `network invalidation prevents no-health-needed fast path from republishing connected`() {
        val lease = TunnelNativeLease()
        val credentialWrites = AtomicInteger()
        val pendingTokenClears = AtomicInteger()
        val connectedPublishes = AtomicInteger()

        assertTrue(lease.advance(21))
        assertTrue(lease.activateRequest(21))
        assertTrue(lease.startIfCurrent(21) { true })
        val cachedVerification = lease.captureVerification(21)!!

        assertEquals(21, lease.invalidateNetwork())
        assertEquals(
            TunnelVerificationCommit.STALE,
            lease.commitVerification(
                cachedVerification,
                writeCredential = { credentialWrites.incrementAndGet(); true },
                clearPendingToken = { pendingTokenClears.incrementAndGet() },
                publishConnected = { connectedPublishes.incrementAndGet() },
            ),
        )

        assertEquals(0, credentialWrites.get())
        assertEquals(0, pendingTokenClears.get())
        assertEquals(0, connectedPublishes.get())
    }

    @Test
    fun `current verification commits credential clear and connected atomically once`() {
        val lease = TunnelNativeLease()
        val credentialWrites = AtomicInteger()
        val pendingTokenClears = AtomicInteger()
        val connectedPublishes = AtomicInteger()

        assertTrue(lease.advance(22))
        assertTrue(lease.activateRequest(22))
        assertTrue(lease.startIfCurrent(22) { true })

        assertEquals(
            TunnelVerificationCommit.COMMITTED,
            lease.commitVerification(
                lease.captureVerification(22)!!,
                writeCredential = { credentialWrites.incrementAndGet(); true },
                clearPendingToken = { pendingTokenClears.incrementAndGet() },
                publishConnected = { connectedPublishes.incrementAndGet() },
            ),
        )
        assertEquals(1, credentialWrites.get())
        assertEquals(1, pendingTokenClears.get())
        assertEquals(1, connectedPublishes.get())
    }

    @Test
    fun `delayed old cleanup cannot stop a newer native lease`() {
        val lease = TunnelNativeLease()
        val oldCleanupMayRun = CountDownLatch(1)
        val nativeStops = AtomicInteger()

        assertTrue(lease.advance(1))
        assertTrue(lease.startIfCurrent(1) { true })
        val delayedOldCleanup = thread {
            oldCleanupMayRun.await()
            lease.stopIfOwner(1) { nativeStops.incrementAndGet() }
        }

        assertTrue(lease.advance(2))
        assertTrue(lease.stopForReplacement(2) { nativeStops.incrementAndGet() })
        assertTrue(lease.startIfCurrent(2) { true })
        oldCleanupMayRun.countDown()
        delayedOldCleanup.join()

        assertEquals(1, nativeStops.get())
        assertEquals(2, lease.ownerGeneration())
    }

    @Test
    fun `network event captured for old generation cannot stop replacement`() {
        val lease = TunnelNativeLease()
        val nativeStops = AtomicInteger()

        assertTrue(lease.advance(11))
        assertTrue(lease.startIfCurrent(11) { true })
        assertTrue(lease.advance(12))
        assertTrue(lease.stopForReplacement(12) { nativeStops.incrementAndGet() })
        assertTrue(lease.startIfCurrent(12) { true })

        assertFalse(lease.stopIfOwner(11) { nativeStops.incrementAndGet() })
        assertEquals(1, nativeStops.get())
        assertEquals(12, lease.ownerGeneration())
    }

    @Test
    fun `sixteen stop callers each complete once while terminal races drain once`() {
        val completions = TunnelStopCompletion()
        val callbackCounts = AtomicIntegerArray(16)
        val stopSends = AtomicInteger()
        val registrations = ConcurrentLinkedQueue<StopRegistration>()
        val registrationReady = CountDownLatch(16)
        val register = CountDownLatch(1)
        val registrars = List(16) { index ->
            thread {
                registrationReady.countDown()
                register.await()
                registrations += completions.register(
                    { callbackCounts.incrementAndGet(index) },
                ) {
                    stopSends.incrementAndGet()
                    41
                }
            }
        }

        registrationReady.await()
        register.countDown()
        registrars.forEach(Thread::join)

        val terminalReturnedCallbacks = AtomicInteger()
        val matchedTerminals = AtomicInteger()
        val terminalReady = CountDownLatch(16)
        val terminate = CountDownLatch(1)
        val terminals = List(16) {
            thread {
                terminalReady.countDown()
                terminate.await()
                val drain = completions.complete(41)
                if (drain.matched) matchedTerminals.incrementAndGet()
                terminalReturnedCallbacks.addAndGet(drain.callbacks.size)
                drain.callbacks.forEach { it() }
            }
        }

        terminalReady.await()
        terminate.countDown()
        terminals.forEach(Thread::join)

        assertEquals(1, registrations.count(StopRegistration::shouldSend))
        assertTrue(registrations.all { it.generation == 41L })
        assertEquals(1, stopSends.get())
        assertEquals(16, matchedTerminals.get())
        assertEquals(16, terminalReturnedCallbacks.get())
        repeat(16) { assertEquals(1, callbackCounts.get(it), "callback $it") }
        assertTrue(completions.complete(41).callbacks.isEmpty())
        assertNull(completions.pendingGeneration())
    }

    @Test
    fun `origin stop is coalesced once and completes every caller`() {
        val completions = CoalescedResultCallbacks<Unit>()
        val callbackCounts = AtomicIntegerArray(16)
        val originStops = AtomicInteger()
        val ready = CountDownLatch(16)
        val register = CountDownLatch(1)
        val callers = List(16) { index ->
            thread {
                ready.countDown()
                register.await()
                if (
                    completions.register { result ->
                        if (result.isSuccess) callbackCounts.incrementAndGet(index)
                    }
                ) {
                    originStops.incrementAndGet()
                }
            }
        }

        ready.await()
        register.countDown()
        callers.forEach(Thread::join)
        completions.complete(Result.success(Unit))

        assertEquals(1, originStops.get())
        repeat(16) { assertEquals(1, callbackCounts.get(it), "callback $it") }
        assertEquals(0, completions.complete(Result.success(Unit)))
    }

    @Test
    fun `superseded origin stop fails callers and releases the next stop`() {
        val completions = CoalescedResultCallbacks<Unit>()
        val firstFailure = AtomicInteger()

        assertTrue(
            completions.register { result ->
                if (result.isFailure) firstFailure.incrementAndGet()
            },
        )
        val superseded = Result.failure<Unit>(IllegalStateException("origin stop superseded"))
        assertEquals(1, completions.complete(superseded))

        assertEquals(1, firstFailure.get())
        assertTrue(completions.register(null))
    }

    @Test
    fun `uppercase trailing slash hostname has the same runtime identity`() {
        val persisted = TunnelRuntimeIdentity.create(
            ReadReceiptsTunnelMode.TOKEN,
            "HTTPS://RECEIPTS.EXAMPLE.COM/",
        )
        val candidate = TunnelRuntimeIdentity.create(
            ReadReceiptsTunnelMode.TOKEN,
            "https://receipts.example.com",
        )

        assertEquals(candidate, persisted)
        assertEquals("https://receipts.example.com", candidate!!.hostname)
        assertFalse(
            tunnelRuntimeChanged(
                ReadReceiptsTunnelMode.TOKEN,
                "HTTPS://RECEIPTS.EXAMPLE.COM/",
                ReadReceiptsTunnelMode.TOKEN,
                "https://receipts.example.com",
            ),
        )
    }

    @Test
    fun `late ACK and timeout cannot complete a newer handoff`() {
        val handoff = TunnelHandoffGate()

        assertNull(handoff.begin(101))
        assertEquals(101, handoff.begin(102))
        assertFalse(handoff.complete(101))
        assertFalse(handoff.fail(101))
        assertTrue(handoff.complete(102))
        assertNull(handoff.pendingGeneration())
    }

    @Test
    fun `replacement drains nested start callback before allocating its generation`() {
        val handoff = TunnelHandoffGate()
        val issuedGeneration = AtomicInteger(100)
        var pendingGeneration: Long? = 100
        val oldCompletions = AtomicInteger()
        val nestedCompletions = AtomicInteger()
        handoff.begin(100)

        val replacementGeneration = handoff.beginAfterSuperseding(
            pendingGeneration = { pendingGeneration },
            supersede = { supersededGeneration ->
                assertTrue(handoff.fail(supersededGeneration))
                pendingGeneration = null
                if (supersededGeneration == 100L) {
                    oldCompletions.incrementAndGet()
                    val nestedGeneration = issuedGeneration.incrementAndGet().toLong()
                    handoff.begin(nestedGeneration)
                    pendingGeneration = nestedGeneration
                } else {
                    nestedCompletions.incrementAndGet()
                }
            },
            generationFactory = { issuedGeneration.incrementAndGet().toLong() },
        )

        assertEquals(1, oldCompletions.get())
        assertEquals(1, nestedCompletions.get())
        assertEquals(102L, replacementGeneration)
        assertEquals(102L, handoff.pendingGeneration())
        assertFalse(handoff.complete(100))
        assertFalse(handoff.fail(100))
        assertEquals(102L, handoff.pendingGeneration())
    }
}
