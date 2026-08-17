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

    private fun TunnelNativeLease.advance(generation: Long): Boolean = advance(generation) {}

    enum class OriginStaleCheckpoint {
        PRE_QUEUE,
        PRE_RECONCILE,
        POST_RECONCILE,
        PRE_SNAPSHOT,
        PRE_PUBLISH,
        PRE_MAIN_DELIVERY,
    }

    @Test
    fun `reserved candidate cannot activate after network invalidation`() {
        val lease = TunnelNativeLease()
        val reservation = lease.advanceAndReserve(10) {}!!

        lease.invalidateNetwork()

        assertFalse(lease.activateReservedRequest(reservation))
        assertFalse(lease.startReservedIfCurrent(reservation) { true })
        assertNull(lease.captureReservedVerification(reservation))
    }

    @Test
    fun `reserved candidate cannot start after network invalidation`() {
        val lease = TunnelNativeLease()
        val reservation = lease.advanceAndReserve(11) {}!!
        assertTrue(lease.activateReservedRequest(reservation))

        lease.invalidateNetwork()

        assertFalse(lease.startReservedIfCurrent(reservation) { true })
        assertNull(lease.captureReservedVerification(reservation))
    }

    @Test
    fun `reserved candidate cannot capture verification after network invalidation`() {
        val lease = TunnelNativeLease()
        val reservation = lease.advanceAndReserve(12) {}!!
        assertTrue(lease.activateReservedRequest(reservation))
        assertTrue(lease.startReservedIfCurrent(reservation) { true })

        assertEquals(12L, lease.invalidateNetwork()!!.invalidatedOwnerGeneration)

        assertNull(lease.captureReservedVerification(reservation))
        assertTrue(lease.stopIfOwner(12) {})
    }

    @Test
    fun `ordinary lease path remains independent of candidate reservations`() {
        val lease = TunnelNativeLease()

        assertTrue(lease.advance(13))
        assertTrue(lease.activateRequest(13))
        assertTrue(lease.startIfCurrent(13) { true })
        assertTrue(lease.captureVerification(13) != null)
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

        assertEquals(30, lease.invalidateNetwork()!!.invalidatedOwnerGeneration)
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

            assertEquals(
                generation,
                lease.invalidateNetwork()!!.invalidatedOwnerGeneration,
                event,
            )
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
    fun `new generation activation requires a fresh native session before verification`() {
        val lease = TunnelNativeLease()

        assertTrue(lease.advance(50))
        assertTrue(lease.activateRequest(50))
        assertTrue(lease.startIfCurrent(50) { true })
        assertTrue(lease.advance(51))
        assertTrue(lease.activateRequest(51))
        assertNull(lease.captureVerification(51))
        assertTrue(lease.stopForReplacement(51) {})
        assertTrue(lease.startIfCurrent(51) { true })
        assertTrue(lease.captureVerification(51) != null)
    }

    @Test
    fun `current generation credential delete cannot shield invalidated session and old ticket cannot stop replacement`() {
        val lease = TunnelNativeLease()
        val nativeStops = AtomicInteger()
        val credentialClears = AtomicInteger()
        val reconnectPublishes = AtomicInteger()
        var administrativeStatus = ReadReceiptsTunnelStatus(
            ReadReceiptsTunnelState.CONNECTED,
            publicUrl = "https://old.example.com",
        )

        assertTrue(lease.advance(60))
        assertTrue(lease.activateRequest(60))
        assertTrue(lease.startIfCurrent(60) { true })
        val invalidation = lease.invalidateNetwork()!!

        assertTrue(
            lease.withCurrentGeneration(60) { sessionState ->
                credentialClears.incrementAndGet()
                administrativeStatus = administrativeStatus.forAdministrativePublish(sessionState)
            },
        )
        assertEquals(ReadReceiptsTunnelState.RECONNECTING, administrativeStatus.state)
        assertNull(administrativeStatus.publicUrl)
        assertEquals(
            60,
            lease.stopInvalidatedSession(
                invalidation,
                stop = { nativeStops.incrementAndGet() },
                publishReconnecting = { generation ->
                    assertEquals(60, generation)
                    administrativeStatus = ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.RECONNECTING,
                    )
                    reconnectPublishes.incrementAndGet()
                },
            ),
        )
        assertEquals(1, credentialClears.get())
        assertEquals(1, nativeStops.get())
        assertEquals(1, reconnectPublishes.get())
        assertEquals(ReadReceiptsTunnelState.RECONNECTING, administrativeStatus.state)
        assertNull(administrativeStatus.publicUrl)
        assertNull(lease.ownerGeneration())
        assertNull(lease.captureVerification(60))

        assertTrue(lease.startIfCurrent(60) { true })
        assertTrue(lease.captureVerification(60) != null)
        assertNull(
            lease.stopInvalidatedSession(
                invalidation,
                stop = { nativeStops.incrementAndGet() },
                publishReconnecting = { reconnectPublishes.incrementAndGet() },
            ),
        )
        assertEquals(1, nativeStops.get())
        assertEquals(1, reconnectPublishes.get())
        assertEquals(60, lease.ownerGeneration())
    }

    @Test
    fun `network teardown publishes reconnecting before a waiting same-generation command can republish status`() {
        val lease = TunnelNativeLease()
        val events = ConcurrentLinkedQueue<String>()
        val status = AtomicReference("CONNECTED")
        val nativeStopEntered = CountDownLatch(1)
        val releaseNativeStop = CountDownLatch(1)
        val commandCompleted = CountDownLatch(1)

        assertTrue(lease.advance(70))
        assertTrue(lease.activateRequest(70))
        assertTrue(lease.startIfCurrent(70) { true })
        val verification = lease.captureVerification(70)!!
        val invalidation = lease.invalidateNetwork()!!

        val teardown = thread {
            lease.stopInvalidatedSession(
                invalidation,
                stop = {
                    events += "native-stop"
                    nativeStopEntered.countDown()
                    releaseNativeStop.await()
                },
                publishReconnecting = {
                    status.set("RECONNECTING")
                    events += "reconnecting"
                },
            )
        }
        nativeStopEntered.await()
        val command = thread {
            lease.withCurrentGeneration(70) {
                status.set(status.get())
                events += "credential-delete"
            }
            commandCompleted.countDown()
        }

        assertFalse(commandCompleted.await(100, TimeUnit.MILLISECONDS))
        releaseNativeStop.countDown()
        teardown.join()
        command.join()

        assertEquals(
            listOf("native-stop", "reconnecting", "credential-delete"),
            events.toList(),
        )
        assertEquals("RECONNECTING", status.get())
        assertEquals(
            TunnelVerificationCommit.STALE,
            lease.commitVerification(
                verification,
                writeCredential = { true },
                clearPendingToken = {},
                publishConnected = { status.set("CONNECTED") },
            ),
        )
        assertEquals("RECONNECTING", status.get())
    }

    @Test
    fun `generation transition and invalidated teardown publish against one authoritative generation`() {
        listOf(
            "START" to ReadReceiptsTunnelState.STARTING,
            "STOP" to ReadReceiptsTunnelState.STOPPING,
        ).forEach { (command, transitionState) ->
            val lease = TunnelNativeLease()
            val serviceState = AtomicReference(
                80L to
                    ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.CONNECTED,
                        publicUrl = "https://old.example.com",
                    ),
            )
            val transitionEntered = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            val teardownPublished = CountDownLatch(1)
            val transitionAccepted = AtomicBoolean()
            val publishedOwnerGeneration = AtomicLong(-1)

            assertTrue(lease.advance(80))
            assertTrue(lease.activateRequest(80))
            assertTrue(lease.startIfCurrent(80) { true })
            val invalidation = lease.invalidateNetwork()!!

            val transition = thread {
                transitionAccepted.set(
                    lease.advance(81) {
                        transitionEntered.countDown()
                        releaseTransition.await()
                        serviceState.set(
                            81L to ReadReceiptsTunnelStatus(transitionState),
                        )
                    },
                )
            }
            transitionEntered.await()
            val teardown = thread {
                lease.stopInvalidatedSession(
                    invalidation,
                    stop = {},
                    publishReconnecting = { ownerGeneration ->
                        publishedOwnerGeneration.set(ownerGeneration)
                        serviceState.set(
                            ownerGeneration to
                                ReadReceiptsTunnelStatus(
                                    ReadReceiptsTunnelState.RECONNECTING,
                                ),
                        )
                        teardownPublished.countDown()
                    },
                )
            }

            assertFalse(teardownPublished.await(100, TimeUnit.MILLISECONDS), command)
            releaseTransition.countDown()
            transition.join()
            teardown.join()
            assertTrue(transitionAccepted.get(), command)
            assertEquals(81, publishedOwnerGeneration.get(), command)
            val forwardSnapshot = serviceState.get()
            assertEquals(81, forwardSnapshot.first, command)
            assertEquals(ReadReceiptsTunnelState.RECONNECTING, forwardSnapshot.second.state, command)
            assertNull(forwardSnapshot.second.publicUrl, command)

            val reverseLease = TunnelNativeLease()
            val reverseState = AtomicReference(
                90L to
                    ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.CONNECTED,
                        publicUrl = "https://old.example.com",
                    ),
            )
            val stopEntered = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val reversePublishedOwner = AtomicLong(-1)
            val reverseTransitionAccepted = AtomicBoolean()
            assertTrue(reverseLease.advance(90))
            assertTrue(reverseLease.activateRequest(90))
            assertTrue(reverseLease.startIfCurrent(90) { true })
            val reverseInvalidation = reverseLease.invalidateNetwork()!!

            val reverseTeardown = thread {
                reverseLease.stopInvalidatedSession(
                    reverseInvalidation,
                    stop = {
                        stopEntered.countDown()
                        releaseStop.await()
                    },
                    publishReconnecting = { ownerGeneration ->
                        reversePublishedOwner.set(ownerGeneration)
                        reverseState.set(
                            ownerGeneration to
                                ReadReceiptsTunnelStatus(
                                    ReadReceiptsTunnelState.RECONNECTING,
                                ),
                        )
                    },
                )
            }
            stopEntered.await()
            val reverseTransition = thread {
                reverseTransitionAccepted.set(
                    reverseLease.advance(91) {
                        reverseState.set(
                            91L to ReadReceiptsTunnelStatus(transitionState),
                        )
                    },
                )
            }
            releaseStop.countDown()
            reverseTeardown.join()
            reverseTransition.join()
            assertTrue(reverseTransitionAccepted.get(), command)
            assertEquals(90, reversePublishedOwner.get(), command)
            val reverseSnapshot = reverseState.get()
            assertEquals(91, reverseSnapshot.first, command)
            assertEquals(transitionState, reverseSnapshot.second.state, command)
            assertNull(reverseSnapshot.second.publicUrl, command)
        }
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
        assertEquals(20, lease.invalidateNetwork()!!.invalidatedOwnerGeneration)
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

        assertEquals(21, lease.invalidateNetwork()!!.invalidatedOwnerGeneration)
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
        val callbackFailures = AtomicIntegerArray(16)
        val stopSends = AtomicInteger()
        val registrations = ConcurrentLinkedQueue<StopRegistration>()
        val registrationReady = CountDownLatch(16)
        val register = CountDownLatch(1)
        val registrars = List(16) { index ->
            thread {
                registrationReady.countDown()
                register.await()
                registrations += completions.register(
                    { result ->
                        callbackCounts.incrementAndGet(index)
                        if (result.isFailure) callbackFailures.incrementAndGet(index)
                    },
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
                drain.callbacks.forEach { it(Result.success(Unit)) }
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
        repeat(16) { assertEquals(0, callbackFailures.get(it), "callback $it") }
        assertTrue(completions.complete(41).callbacks.isEmpty())
        assertNull(completions.pendingGeneration())
    }

    @Test
    fun `stop success and timeout deliver distinct typed results once`() {
        val completions = TunnelStopCompletion()
        val nextGeneration = AtomicLong()
        val successResults = mutableListOf<Result<Unit>>()
        val timeoutResults = mutableListOf<Result<Unit>>()

        val successful = completions.register({ result -> successResults += result }) {
            nextGeneration.incrementAndGet()
        }
        completions.complete(successful.generation).callbacks.forEach {
            it(Result.success(Unit))
        }

        val timedOut = completions.register({ result -> timeoutResults += result }) {
            nextGeneration.incrementAndGet()
        }
        completions.completeTimeout(timedOut.generation, timedOut.generation).callbacks.forEach {
            it(Result.failure(IllegalStateException("隧道停止超时")))
        }

        assertEquals(1, successResults.size)
        assertTrue(successResults.single().isSuccess)
        assertEquals(1, timeoutResults.size)
        assertTrue(timeoutResults.single().isFailure)
        assertEquals("隧道停止超时", timeoutResults.single().exceptionOrNull()!!.message)
    }

    @Test
    fun `tunnel stop failure still tears down origin and wins stack result`() {
        val originStops = AtomicInteger()
        val delivered = AtomicReference<OriginRequestTerminal<Unit>>()

        finishBuiltInStackStop(
            tunnelResult = Result.failure(IllegalStateException("隧道停止超时")),
            stopOrigin = { complete ->
                originStops.incrementAndGet()
                complete(7, OriginRequestTerminal.Completed(Result.success(Unit)))
            },
            onFinished = { _, terminal -> delivered.set(terminal) },
        )

        assertEquals(1, originStops.get())
        val completed = delivered.get() as OriginRequestTerminal.Completed
        assertTrue(completed.result.isFailure)
        assertEquals("隧道停止超时", completed.result.exceptionOrNull()!!.message)
    }

    @Test
    fun `rollback restart failure is surfaced without connector details`() {
        val terminal = configurationRollbackTerminal(
            originalFailure = IllegalStateException("candidate failed"),
            restartTerminal = OriginRequestTerminal.Completed(
                Result.failure(IllegalStateException("token=raw-connector-secret")),
            ),
        ) as OriginRequestTerminal.Completed

        assertTrue(terminal.result.isFailure)
        val failure = terminal.result.exceptionOrNull()!!
        assertNull(failure.message)
        assertFalse(failure.toString().contains("raw-connector-secret"))
    }

    @Test
    fun `pending stop upgrades past externally observed authoritative generation and completes all callers only at upgraded terminal`() {
        val completions = TunnelStopCompletion()
        val issuedGeneration = AtomicLong(100)
        val firstCallback = AtomicInteger()
        val secondCallback = AtomicInteger()

        val firstStop = completions.register(
            callback = { firstCallback.incrementAndGet() },
            latestIssuedGeneration = issuedGeneration.get(),
            generationFactory = issuedGeneration::incrementAndGet,
        )
        assertTrue(firstStop.shouldSend)
        assertEquals(101, firstStop.generation)

        val externallyObservedGeneration = issuedGeneration.incrementAndGet()
        assertFalse(
            completions.completeTimeout(
                generation = firstStop.generation,
                authoritativeGeneration = externallyObservedGeneration,
            ).matched,
        )
        assertEquals(0, firstCallback.get())

        val upgradedStop = completions.register(
            callback = { secondCallback.incrementAndGet() },
            latestIssuedGeneration = issuedGeneration.get(),
            generationFactory = issuedGeneration::incrementAndGet,
        )
        assertTrue(upgradedStop.shouldSend)
        assertEquals(103, upgradedStop.generation)
        assertEquals(103, completions.pendingGeneration())

        assertFalse(completions.complete(firstStop.generation).matched)
        assertFalse(completions.complete(externallyObservedGeneration).matched)
        assertEquals(0, firstCallback.get())
        assertEquals(0, secondCallback.get())

        val terminal = completions.completeTimeout(
            generation = upgradedStop.generation,
            authoritativeGeneration = upgradedStop.generation,
        )
        assertTrue(terminal.matched)
        assertEquals(2, terminal.callbacks.size)
        terminal.callbacks.forEach {
            it(Result.failure(IllegalStateException("隧道停止超时")))
        }
        assertEquals(1, firstCallback.get())
        assertEquals(1, secondCallback.get())
        assertTrue(completions.complete(upgradedStop.generation).callbacks.isEmpty())
    }

    @Test
    fun `pending stop rejects credential delete without allocating or sending and completes once`() {
        val completions = TunnelStopCompletion()
        val issuedGeneration = AtomicLong(100)
        val stopCallback = AtomicInteger()
        val deleteSends = AtomicInteger()
        val sentGeneration = AtomicLong(-1)

        val stop = completions.register(
            callback = { stopCallback.incrementAndGet() },
            latestIssuedGeneration = issuedGeneration.get(),
            generationFactory = issuedGeneration::incrementAndGet,
        )
        assertEquals(101, stop.generation)

        assertFalse(
            completions.runAdministrativeCommandIfIdle(
                hasPendingStart = { false },
                command = {
                    sentGeneration.set(issuedGeneration.get())
                    deleteSends.incrementAndGet()
                },
            ),
        )
        assertEquals(101, issuedGeneration.get())
        assertEquals(-1, sentGeneration.get())
        assertEquals(0, deleteSends.get())

        val terminal = completions.completeTimeout(stop.generation, issuedGeneration.get())
        assertTrue(terminal.matched)
        terminal.callbacks.forEach {
            it(Result.failure(IllegalStateException("隧道停止超时")))
        }
        assertEquals(1, stopCallback.get())
        assertTrue(completions.complete(stop.generation).callbacks.isEmpty())

        assertFalse(
            completions.runAdministrativeCommandIfIdle(
                hasPendingStart = { true },
                command = { deleteSends.incrementAndGet() },
            ),
        )
        assertEquals(0, deleteSends.get())
    }

    @Test
    fun `pending stop rejects connector start admission without allocating generation token or send`() {
        val completions = TunnelStopCompletion()
        val issuedGeneration = AtomicLong(100)
        val startGenerationAllocations = AtomicInteger()
        val tokenUses = AtomicInteger()
        val startSends = AtomicInteger()

        val stop = completions.register(
            callback = null,
            latestIssuedGeneration = issuedGeneration.get(),
            generationFactory = issuedGeneration::incrementAndGet,
        )
        assertEquals(101, stop.generation)

        val admission = completions.startAdmission {
            startGenerationAllocations.incrementAndGet()
            issuedGeneration.incrementAndGet()
        }
        when (admission) {
            is TunnelStartAdmission.Admitted -> {
                tokenUses.incrementAndGet()
                startSends.incrementAndGet()
            }
            is TunnelStartAdmission.Rejected -> Unit
        }

        assertEquals(101, issuedGeneration.get())
        assertEquals(0, startGenerationAllocations.get())
        assertEquals(0, tokenUses.get())
        assertEquals(0, startSends.get())
        assertTrue(admission is TunnelStartAdmission.Rejected)
        assertEquals(
            ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
            (admission as TunnelStartAdmission.Rejected).failure.errorCode,
        )
    }

    @Test
    fun `connector start reservation linearizes before stop and stop completes newer generation once`() {
        val completions = TunnelStopCompletion()
        val issuedGeneration = AtomicLong(100)
        val reservationEntered = CountDownLatch(1)
        val releaseReservation = CountDownLatch(1)
        val startAdmission = AtomicReference<TunnelStartAdmission>()
        val stopCallStarted = CountDownLatch(1)
        val stopRegistration = AtomicReference<StopRegistration>()
        val stopCallback = AtomicInteger()

        val starter = thread {
            startAdmission.set(
                completions.startAdmission {
                    reservationEntered.countDown()
                    releaseReservation.await()
                    issuedGeneration.incrementAndGet()
                },
            )
        }
        assertTrue(reservationEntered.await(5, TimeUnit.SECONDS))

        val stopper = thread {
            stopCallStarted.countDown()
            stopRegistration.set(
                completions.register(
                    callback = { stopCallback.incrementAndGet() },
                    latestIssuedGeneration = issuedGeneration.get(),
                    generationFactory = issuedGeneration::incrementAndGet,
                ),
            )
        }
        assertTrue(stopCallStarted.await(5, TimeUnit.SECONDS))
        try {
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                stopper.state != Thread.State.BLOCKED && stopper.isAlive &&
                System.nanoTime() < blockedDeadline
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, stopper.state)
        } finally {
            releaseReservation.countDown()
        }
        starter.join(5_000)
        stopper.join(5_000)
        assertFalse(starter.isAlive)
        assertFalse(stopper.isAlive)

        val admitted = startAdmission.get() as TunnelStartAdmission.Admitted
        val stop = stopRegistration.get()
        assertEquals(101, admitted.generation)
        assertTrue(stop.shouldSend)
        assertEquals(102, stop.generation)
        assertEquals(102, issuedGeneration.get())

        assertFalse(completions.complete(admitted.generation).matched)
        val drain = completions.complete(stop.generation)
        assertTrue(drain.matched)
        drain.callbacks.forEach { it(Result.success(Unit)) }
        assertEquals(1, stopCallback.get())
        assertTrue(completions.complete(stop.generation).callbacks.isEmpty())
        assertTrue(
            completions.completeTimeout(
                generation = stop.generation,
                authoritativeGeneration = issuedGeneration.get(),
            ).callbacks.isEmpty(),
        )
        assertEquals(1, stopCallback.get())
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

    @ParameterizedTest
    @EnumSource(OriginStaleCheckpoint::class)
    fun `each stale origin checkpoint supersedes the reconciled terminal once`(
        staleCheckpoint: OriginStaleCheckpoint,
    ) = runBlocking {
        val currentChecks = AtomicInteger()
        val reconciles = AtomicInteger()
        val snapshots = AtomicInteger()
        val publishes = AtomicInteger()
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

        if (staleCheckpoint == OriginStaleCheckpoint.PRE_MAIN_DELIVERY) {
            val completed = terminal as OriginRequestTerminal.Completed
            assertEquals(8123, completed.result.getOrThrow())
        } else {
            assertSame(OriginRequestTerminal.Superseded, terminal, "$staleCheckpoint")
        }
        assertEquals(
            if (staleCheckpoint == OriginStaleCheckpoint.PRE_QUEUE ||
                staleCheckpoint == OriginStaleCheckpoint.PRE_RECONCILE
            ) 0 else 1,
            reconciles.get(),
            "$staleCheckpoint",
        )
        assertEquals(
            if (staleCheckpoint >= OriginStaleCheckpoint.PRE_PUBLISH) 1 else 0,
            snapshots.get(),
            "$staleCheckpoint",
        )
        assertEquals(
            if (staleCheckpoint == OriginStaleCheckpoint.PRE_MAIN_DELIVERY) 1 else 0,
            publishes.get(),
            "$staleCheckpoint",
        )
    }

    @Test
    fun `coalesced stop revalidates remaining owners after current callback reenters`() {
        val callbacks = CoalescedOriginCallbacks<Unit>()
        val originGeneration = AtomicLong(70)
        val currentTerminal = AtomicReference<OriginRequestTerminal<Unit>>()
        val staleTerminal = AtomicReference<OriginRequestTerminal<Unit>>()
        val staleSaves = AtomicInteger()
        val staleRestarts = AtomicInteger()
        val staleStarts = AtomicInteger()

        assertTrue(
            callbacks.register { terminal ->
                staleTerminal.set(terminal)
                when (terminal) {
                    is OriginRequestTerminal.Completed -> {
                        staleSaves.incrementAndGet()
                        staleRestarts.incrementAndGet()
                        staleStarts.incrementAndGet()
                    }

                    OriginRequestTerminal.Superseded -> Unit
                }
            },
        )
        assertFalse(
            callbacks.register { terminal ->
                currentTerminal.set(terminal)
                when (terminal) {
                    is OriginRequestTerminal.Completed -> originGeneration.incrementAndGet()
                    OriginRequestTerminal.Superseded -> Unit
                }
            },
        )

        assertEquals(
            2,
            callbacks.complete(
                OriginRequestTerminal.Completed(Result.success(Unit)),
                isCurrent = { originGeneration.get() == 70L },
            ),
        )
        assertTrue(currentTerminal.get() is OriginRequestTerminal.Completed)
        assertSame(OriginRequestTerminal.Superseded, staleTerminal.get())
        assertEquals(0, staleSaves.get())
        assertEquals(0, staleRestarts.get())
        assertEquals(0, staleStarts.get())
    }

    @Test
    fun `coalesced stop completes only the newest owner without reentry`() {
        val callbacks = CoalescedOriginCallbacks<Unit>()
        val oldTerminal = AtomicReference<OriginRequestTerminal<Unit>>()
        val newestTerminal = AtomicReference<OriginRequestTerminal<Unit>>()

        assertTrue(callbacks.register(oldTerminal::set))
        assertFalse(callbacks.register(newestTerminal::set))

        assertEquals(
            2,
            callbacks.complete(
                OriginRequestTerminal.Completed(Result.success(Unit)),
                isCurrent = { true },
            ),
        )
        assertTrue(newestTerminal.get() is OriginRequestTerminal.Completed)
        assertSame(OriginRequestTerminal.Superseded, oldTerminal.get())
    }

    @Test
    fun `new configuration transaction prevents delayed browser authority from replacing it`() {
        val ownership = ConfigurationTransactionOwnership()
        val persistedModes = mutableListOf<ReadReceiptsTunnelMode>()
        val delayedBrowser = ownership.acquire()
        val currentToken = ownership.acquire()

        assertFalse(
            delayedBrowser.finishIfCurrent {
                persistedModes += ReadReceiptsTunnelMode.BROWSER_LOGIN
            },
        )
        assertTrue(
            currentToken.finishIfCurrent {
                persistedModes += ReadReceiptsTunnelMode.TOKEN
            },
        )
        assertEquals(listOf(ReadReceiptsTunnelMode.TOKEN), persistedModes)
    }

    @Test
    fun `explicit configuration save supersedes pending browser reconciliation`() {
        val ownership = ConfigurationTransactionOwnership()
        val staleWrites = AtomicInteger()
        val pendingBrowser = ownership.acquire()

        assertTrue(pendingBrowser.isCurrent())
        ownership.supersede()

        assertFalse(pendingBrowser.isCurrent())
        assertFalse(pendingBrowser.runIfCurrent(staleWrites::incrementAndGet))
        assertEquals(0, staleWrites.get())
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
    fun `handoff replacement is superseded while genuine failure stays completed`() {
        val replacedTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Unit>>()
        val replaced = TunnelHandoffTerminalDelivery(replacedTerminals::add)

        assertTrue(replaced.supersede())
        assertFalse(replaced.complete(Result.failure(IllegalStateException("late failure"))))
        assertSame(OriginRequestTerminal.Superseded, replacedTerminals.single())

        val failure = IllegalStateException("service rejected")
        val failedTerminals = ConcurrentLinkedQueue<OriginRequestTerminal<Unit>>()
        val failed = TunnelHandoffTerminalDelivery(failedTerminals::add)

        assertTrue(failed.complete(Result.failure(failure)))
        val completed = failedTerminals.single() as OriginRequestTerminal.Completed
        assertSame(failure, completed.result.exceptionOrNull())
    }

    @Test
    fun `stop drains callback-created replacement before allocating its command`() {
        val handoff = TunnelHandoffGate()
        val events = mutableListOf<String>()
        var pendingGeneration: Long? = 100
        handoff.begin(100)

        handoff.drainPending(
            pendingGeneration = { pendingGeneration },
            supersede = { generation ->
                assertTrue(handoff.fail(generation))
                pendingGeneration = null
                events += "superseded:$generation"
                if (generation == 100L) {
                    handoff.begin(101)
                    pendingGeneration = 101
                }
            },
        )
        events += "stop-allocated"

        assertEquals(
            listOf("superseded:100", "superseded:101", "stop-allocated"),
            events,
        )
        assertNull(pendingGeneration)
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
