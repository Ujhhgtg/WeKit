package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class ReadReceiptsTunnelCoordinationTest {

    enum class OriginStaleCheckpoint {
        PRE_QUEUE,
        PRE_RECONCILE,
        POST_RECONCILE,
        PRE_SNAPSHOT,
        PRE_PUBLISH,
        PRE_MAIN_DELIVERY,
    }

    private enum class OriginOperation {
        START,
        STOP,
    }

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
    fun `completed and superseded delivery attempts invoke the origin owner once`() {
        val ownerTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Int>>()
        val delivery = OriginTerminalDelivery<Int>(ownerTerminals::add)
        val ready = CountDownLatch(16)
        val deliver = CountDownLatch(1)
        val attempts = List(16) { index ->
            thread {
                ready.countDown()
                deliver.await()
                if (index % 2 == 0) {
                    delivery.deliver(OriginRequestTerminal.Completed(Result.success(index)))
                } else {
                    delivery.deliver(OriginRequestTerminal.Superseded)
                }
            }
        }

        ready.await()
        deliver.countDown()
        attempts.forEach(Thread::join)

        assertEquals(1, ownerTerminals.size)
        assertFalse(
            delivery.deliver(OriginRequestTerminal.Completed(Result.success(99))),
        )
        assertFalse(delivery.deliver(OriginRequestTerminal.Superseded))
        assertEquals(1, ownerTerminals.size)
    }

    @Test
    fun `replacement allocation cannot interleave final check and old owner callback`() =
        runBlocking {
            val generation = AtomicLong(1)
            val ownerTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Int>>()
            val execution = OriginRequestExecution<Int, String>(
                isCurrent = { generation.get() == 1L },
                lifecycleMutex = Mutex(),
            )
            val terminal = execution.execute(
                reconcile = {
                    OriginRequestTerminal.Completed(Result.success(8123))
                },
                snapshot = { "running:8123" },
                publish = { _, _ -> true },
            )
            val boundary = OriginRequestBoundary()
            val callbackStarted = CountDownLatch(1)
            val finishCallback = CountDownLatch(1)
            val replacementAttempting = CountDownLatch(1)
            val replacementAllocated = CountDownLatch(1)
            val callbackFinished = AtomicBoolean()
            val replacementObservedFinishedCallback = AtomicBoolean()
            val delivery = OriginTerminalDelivery<Int> { delivered ->
                callbackStarted.countDown()
                finishCallback.await()
                ownerTerminals += delivered
                callbackFinished.set(true)
            }
            val deliveryThread = thread {
                boundary.deliverCurrent(
                    delivery = delivery,
                    terminal = terminal,
                    isCurrent = { generation.get() == 1L },
                )
            }

            callbackStarted.await()
            val replacementThread = thread {
                replacementAttempting.countDown()
                boundary.mutate {
                    replacementObservedFinishedCallback.set(callbackFinished.get())
                    generation.incrementAndGet()
                    replacementAllocated.countDown()
                }
            }
            replacementAttempting.await()
            val allocatedDuringCallback = replacementAllocated.await(250, TimeUnit.MILLISECONDS)
            finishCallback.countDown()
            deliveryThread.join()
            replacementThread.join()

            assertFalse(allocatedDuringCallback)
            assertTrue(replacementObservedFinishedCallback.get())
            val completed = ownerTerminals.single() as OriginRequestTerminal.Completed
            assertEquals(8123, completed.result.getOrThrow())
            assertEquals(2, generation.get())
        }

    @ParameterizedTest
    @EnumSource(OriginStaleCheckpoint::class)
    fun `each stale origin checkpoint supersedes old start and stop owners once`(
        staleCheckpoint: OriginStaleCheckpoint,
    ) = runBlocking {
        OriginOperation.entries.forEach { operation ->
            val currentChecks = AtomicInteger()
            val rollbacks = AtomicInteger()
            val saves = AtomicInteger()
            val starts = AtomicInteger()
            val stops = AtomicInteger()
            val reconciles = AtomicInteger()
            val snapshots = AtomicInteger()
            val publishes = AtomicInteger()
            val oldOwnerTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Int?>>()
            val execution = OriginRequestExecution<Int?, String>(
                isCurrent = {
                    currentChecks.getAndIncrement() < staleCheckpoint.ordinal
                },
                lifecycleMutex = Mutex(),
            )
            val terminal = execution.execute(
                reconcile = {
                    reconciles.incrementAndGet()
                    OriginRequestTerminal.Completed(Result.success(8123))
                },
                snapshot = {
                    snapshots.incrementAndGet()
                    "running:8123"
                },
                publish = { _, _ ->
                    publishes.incrementAndGet()
                    true
                },
            )
            val delivery = OriginTerminalDelivery<Int?> { delivered ->
                oldOwnerTerminals += delivered
                when (delivered) {
                    is OriginRequestTerminal.Completed -> {
                        rollbacks.incrementAndGet()
                        saves.incrementAndGet()
                        when (operation) {
                            OriginOperation.START -> starts.incrementAndGet()
                            OriginOperation.STOP -> stops.incrementAndGet()
                        }
                    }

                    OriginRequestTerminal.Superseded -> Unit
                }
            }
            val boundary = OriginRequestBoundary()

            assertTrue(
                boundary.deliverCurrent(
                    delivery = delivery,
                    terminal = terminal,
                    isCurrent = {
                        currentChecks.getAndIncrement() < staleCheckpoint.ordinal
                    },
                ),
            )
            assertEquals(1, oldOwnerTerminals.size, "$operation at $staleCheckpoint")
            assertSame(
                OriginRequestTerminal.Superseded,
                oldOwnerTerminals.single(),
                "$operation at $staleCheckpoint",
            )
            assertEquals(0, rollbacks.get(), "$operation at $staleCheckpoint")
            assertEquals(0, saves.get(), "$operation at $staleCheckpoint")
            assertEquals(0, starts.get(), "$operation at $staleCheckpoint")
            assertEquals(0, stops.get(), "$operation at $staleCheckpoint")
            assertEquals(
                if (staleCheckpoint == OriginStaleCheckpoint.PRE_QUEUE ||
                    staleCheckpoint == OriginStaleCheckpoint.PRE_RECONCILE
                ) 0 else 1,
                reconciles.get(),
                "$operation at $staleCheckpoint",
            )
            assertEquals(
                if (staleCheckpoint >= OriginStaleCheckpoint.PRE_PUBLISH) 1 else 0,
                snapshots.get(),
                "$operation at $staleCheckpoint",
            )
            assertEquals(
                if (staleCheckpoint == OriginStaleCheckpoint.PRE_MAIN_DELIVERY) 1 else 0,
                publishes.get(),
                "$operation at $staleCheckpoint",
            )
        }

        val replacementOwnerTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Int?>>()
        val replacementExecution = OriginRequestExecution<Int?, String>(
            isCurrent = { true },
            lifecycleMutex = Mutex(),
        )
        val replacementTerminal = replacementExecution.execute(
            reconcile = {
                OriginRequestTerminal.Completed(Result.success(9123))
            },
            snapshot = { "running:9123" },
            publish = { _, _ -> true },
        )
        val replacementDelivery = OriginTerminalDelivery<Int?>(replacementOwnerTerminals::add)
        val replacementBoundary = OriginRequestBoundary()

        assertTrue(
            replacementBoundary.deliverCurrent(
                delivery = replacementDelivery,
                terminal = replacementTerminal,
                isCurrent = { true },
            ),
        )
        val completed = replacementOwnerTerminals.single()
            as OriginRequestTerminal.Completed<Int?>
        assertEquals(9123, completed.result.getOrThrow())
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
