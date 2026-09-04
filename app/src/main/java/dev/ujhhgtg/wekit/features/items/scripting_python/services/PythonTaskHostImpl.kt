package dev.ujhhgtg.wekit.features.items.scripting_python.services

import android.os.Handler
import android.os.Looper
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginScope
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLoader
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLimits
import dev.ujhhgtg.wekit.python.api.PythonTaskHandle
import dev.ujhhgtg.wekit.python.api.PythonTaskHost
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class PythonTaskHostImpl(private val scope: PythonPluginScope) : PythonTaskHost {
    override fun main(task: Callable<Any?>): PythonTaskHandle {
        check(!scope.isClosed) { "Python plugin scope is closed" }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return CompletedTaskHandle(PythonRuntimeLoader.withLookupClassLoader(task::call))
        }
        val done = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<Any?>()
        val wrapped = Runnable {
            started.set(true)
            try {
                if (!scope.isClosed) result.set(PythonRuntimeLoader.withLookupClassLoader(task::call))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.set(true)
                completed.countDown()
            }
        }
        mainHandler.post(wrapped)
        return OwnedTaskHandle(done, started, completed, failure, result, wrapped).also(::own)
    }

    override fun mainAsync(task: Callable<Any?>): PythonTaskHandle = main(task)

    override fun spawn(task: Callable<Any?>): PythonTaskHandle {
        check(!scope.isClosed) { "Python plugin scope is closed" }
        val done = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        val future = executor.submit<Any?> {
            started.set(true)
            try {
                if (!scope.isClosed) PythonRuntimeLoader.withLookupClassLoader(task::call) else null
            } finally {
                done.set(true)
                completed.countDown()
            }
        }
        return FutureTaskHandle(future, done, started, completed).also(::own)
    }

    private fun own(handle: PythonTaskHandle) {
        try {
            scope.track(handle, handle::cancel)
        } catch (error: Throwable) {
            handle.cancel()
            throw error
        }
    }

    private class OwnedTaskHandle(
        private val done: AtomicBoolean,
        private val started: AtomicBoolean,
        private val completed: CountDownLatch,
        private val failure: AtomicReference<Throwable?>,
        private val result: AtomicReference<Any?>,
        private val runnable: Runnable,
    ) : PythonTaskHandle {
        override fun cancel() {
            mainHandler.removeCallbacks(runnable)
            if (!started.get()) {
                done.set(true)
                completed.countDown()
            }
            awaitDrain(completed)
        }
        override fun isDone(): Boolean = done.get()
        override fun awaitResult(): Any? {
            completed.await()
            failure.get()?.let { throw it }
            return result.get()
        }
    }

    private class FutureTaskHandle(
        private val future: Future<*>,
        private val done: AtomicBoolean,
        private val started: AtomicBoolean,
        private val completed: CountDownLatch,
    ) : PythonTaskHandle {
        override fun cancel() {
            if (future.cancel(true) && !started.get()) {
                done.set(true)
                completed.countDown()
            }
            awaitDrain(completed)
        }
        override fun isDone(): Boolean = done.get() || future.isDone
        override fun awaitResult(): Any? = try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private class CompletedTaskHandle(private val result: Any?) : PythonTaskHandle {
        override fun cancel() = Unit
        override fun isDone(): Boolean = true
        override fun awaitResult(): Any? = result
    }

    private companion object {
        private const val TAG = "PythonTaskHost"
        val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "WeKit-Python-Task").apply { isDaemon = true }
        }

        fun awaitDrain(completed: CountDownLatch) {
            if (!completed.await(PythonRuntimeLimits.TASK_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                WeLogger.w(TAG, "Python plugin task leaked past the drain window")
            }
        }
    }
}
