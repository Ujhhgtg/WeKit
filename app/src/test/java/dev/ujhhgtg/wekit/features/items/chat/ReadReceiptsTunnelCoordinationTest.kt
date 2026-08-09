package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReadReceiptsTunnelCoordinationTest {

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
    fun `stop completion drains one generation exactly once under a race`() {
        val completions = TunnelStopCompletion()
        val callbacks = AtomicInteger()
        val unmatchedTerminals = AtomicInteger()
        val registration = completions.register({ callbacks.incrementAndGet() }) { 41 }
        val joined = completions.register({ callbacks.incrementAndGet() }) { 42 }
        val ready = CountDownLatch(16)
        val run = CountDownLatch(1)
        val workers = List(16) {
            thread {
                ready.countDown()
                run.await()
                val drain = completions.complete(registration.generation)
                if (!drain.matched) unmatchedTerminals.incrementAndGet()
                drain.callbacks.forEach { callback ->
                    callback()
                }
            }
        }

        ready.await()
        run.countDown()
        workers.forEach(Thread::join)

        assertTrue(registration.shouldSend)
        assertFalse(joined.shouldSend)
        assertEquals(41, joined.generation)
        assertEquals(1, callbacks.get())
        assertEquals(0, unmatchedTerminals.get())
        assertNull(completions.pendingGeneration())
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
}
