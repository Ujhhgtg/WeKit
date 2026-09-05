package dev.ujhhgtg.wekit.dexkit.resolution

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(15)
class ResolutionCoordinatorTest {
    @Test
    fun diamondDependencyExecutesOnceAndPublishesItsResult() {
        val coordinator = ResolutionCoordinator(listOf("left", "right"), { it })
        val pool = Executors.newFixedThreadPool(2)
        val rootsStarted = CountDownLatch(2)
        val executions = AtomicInteger()
        var published = ""
        try {
            val results = listOf("left", "right").map { root ->
                pool.submit(Callable {
                    coordinator.resolve(root, { false }) {
                        rootsStarted.countDown()
                        assertTrue(rootsStarted.await(5, TimeUnit.SECONDS))
                        coordinator.resolve("shared", { false }) {
                            executions.incrementAndGet()
                            published = "descriptor"
                        }
                        assertEquals("descriptor", published)
                    }
                })
            }
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            coordinator.resolve("shared", { false }) { fail("executed again as a root") }
            assertEquals(1, executions.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun sharedFailureIsNotRetriedAndLeavesIndependentOwnersUsable() {
        val coordinator = ResolutionCoordinator(listOf("left", "right", "other"), { it })
        val failure = IllegalStateException("query failed")
        val executions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("left", "right").map { root ->
                pool.submit(Callable {
                    coordinator.resolve(root, { false }) {
                        coordinator.resolve("shared", { false }) {
                            executions.incrementAndGet()
                            throw failure
                        }
                    }
                })
            }
            results.forEach {
                assertSame(failure, assertThrows(ExecutionException::class.java) {
                    it.get(5, TimeUnit.SECONDS)
                }.cause)
            }
            assertEquals(1, executions.get())
            var ran = false
            coordinator.resolve("other", { false }) { ran = true }
            assertTrue(ran)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun crossThreadCycleFailsBothRootsInsteadOfDeadlocking() {
        val coordinator = ResolutionCoordinator(listOf("a", "b"), { it })
        val running = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("a" to "b", "b" to "a").map { (owner, dependency) ->
                pool.submit(Callable {
                    coordinator.resolve(owner, { false }) {
                        running.countDown()
                        assertTrue(running.await(5, TimeUnit.SECONDS))
                        coordinator.resolve(dependency, { false }) { fail("duplicate execution") }
                    }
                })
            }
            results.forEach {
                val error = assertThrows(ExecutionException::class.java) { it.get(5, TimeUnit.SECONDS) }
                assertTrue(error.cause!!.message!!.contains("wait cycle"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun cancellationReleasesWaiterBeforeRunningQueryReturns() {
        val cancelled = AtomicBoolean()
        val waitingStarted = CountDownLatch(1)
        val coordinator = ResolutionCoordinator(listOf("query"), { it }) {
            if (Thread.currentThread().name == "waiting-consumer") waitingStarted.countDown()
            if (cancelled.get()) throw CancellationException("cancelled")
        }
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val query = pool.submit(Callable {
                coordinator.resolve("query", { false }) {
                    queryStarted.countDown()
                    assertTrue(releaseQuery.await(5, TimeUnit.SECONDS))
                }
            })
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
            val waiter = pool.submit(Callable {
                Thread.currentThread().name = "waiting-consumer"
                coordinator.resolve("query", { false }) { fail("duplicate execution") }
            })
            assertTrue(waitingStarted.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertInstanceOf(CancellationException::class.java,
                assertThrows(ExecutionException::class.java) { waiter.get(5, TimeUnit.SECONDS) }.cause)
            assertFalse(query.isDone)
            releaseQuery.countDown()
            assertInstanceOf(CancellationException::class.java,
                assertThrows(ExecutionException::class.java) { query.get(5, TimeUnit.SECONDS) }.cause)
        } finally {
            releaseQuery.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rootsOverrideCachedValuesAndNestedDependenciesRunOnTheCurrentWorker() {
        val coordinator = ResolutionCoordinator(listOf("root"), { it })
        val thread = Thread.currentThread()
        coordinator.resolve("root", { fail("must not reuse a requested root") }) {
            assertTrue(coordinator.isOwnedByCurrentThread("root"))
            coordinator.resolve("dependency", { false }) {
                assertSame(thread, Thread.currentThread())
                assertTrue(coordinator.isOwnedByCurrentThread("root"))
            }
            coordinator.resolve("cached", { true }) { fail("should reuse cached dependency") }
        }
        assertFalse(coordinator.isOwnedByCurrentThread("root"))
    }
}
