package dev.ujhhgtg.wekit.loader.entry.zygisk

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/** Callback state and IHookBridge dispatch for LSPlant's generated bridges. */
@Keep
object ArtHookBridgeRuntime {

    private const val TAG = "ArtHookBridgeRuntime"

    /** Each handle owns one registration, even when callback objects are shared. */
    class PrioritizedCallback(
        val callback: IHookBridge.IMemberHookCallback,
        val priority: Int,
    )

    /** LSPlant retains this instance as its generated bridge's hooker object. */
    @Keep
    class HookEntry(val member: Member) {
        val callbacks: CopyOnWriteArrayList<PrioritizedCallback> = CopyOnWriteArrayList()
        private val isStatic = Modifier.isStatic(member.modifiers)
        private val installationFinished = CountDownLatch(1)
        @Volatile private var backupMethod: Method? = null
        @Volatile private var installationThread: Thread? = Thread.currentThread()
        private var installationFailure: Throwable? = null

        val isInstalled: Boolean get() = backupMethod != null

        fun completeInstallation(backup: Method) {
            // LSPlant has already made the reflected backup accessible.
            backupMethod = backup
            installationThread = null
            installationFinished.countDown()
        }

        fun failInstallation(failure: Throwable) {
            installationFailure = failure
            installationThread = null
            installationFinished.countDown()
        }

        fun awaitBackup(): Method {
            backupMethod?.let { return it }
            // Class loading inside LSPlant can re-enter through another hook;
            // its installer cannot wait for itself to finish publishing backup.
            check(Thread.currentThread() !== installationThread) {
                "$TAG: reentrant access while LSPlant is installing $member"
            }
            // ART can run the replacement before Hook returns its backup. Park
            // without holding any Java monitor, and preserve the host thread's
            // interrupt status instead of injecting InterruptedException into it.
            var interrupted = false
            try {
                while (true) {
                    try {
                        installationFinished.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            return backupMethod ?: throw IllegalStateException(
                "$TAG: LSPlant installation failed for $member", installationFailure,
            )
        }

        fun addCallback(registration: PrioritizedCallback) {
            // Publish a single new array so dispatch never observes an empty
            // intermediate list. Equal priorities retain registration order.
            synchronized(callbacks) {
                val index = callbacks.indexOfFirst { it.priority < registration.priority }
                if (index == -1) callbacks.add(registration) else callbacks.add(index, registration)
            }
        }

        fun removeCallback(registration: PrioritizedCallback) {
            // Share the ordering lock with insertion: its chosen index must not
            // become stale when another thread removes a callback concurrently.
            synchronized(callbacks) { callbacks.remove(registration) }
        }

        /** Required LSPlant signature: public Object callback(Object[] args). */
        @Keep
        fun callback(rawArgs: Array<Any?>): Any? {
            awaitBackup()
            // LSPlant prepends the receiver only for instance methods and
            // constructors. IHookBridge exposes it separately from args.
            val receiver = if (isStatic) null else rawArgs[0]
            val args = if (isStatic) rawArgs else rawArgs.copyOfRange(1, rawArgs.size)
            return dispatch(this, receiver, args)
        }

        fun invokeOriginal(thisObj: Any?, args: Array<Any?>): Any? {
            val backup = awaitBackup()
            return try {
                val result = backup.invoke(if (isStatic) null else thisObj, *args)
                if (member is Constructor<*>) null else result
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }

    // ── Mutable hook param ────────────────────────────────────────────────────

    class MutableHookParam(
        override val member: Member,
        override val thisObject: Any?,
        val mutableArgs: Array<Any?>,
    ) : IHookBridge.IMemberHookParam {
        override val args: Array<Any?> get() = mutableArgs
        private var resultValue: Any? = null
        private var throwableValue: Throwable? = null
        var resultSet: Boolean = false
        // Legacy Xposed gives each callback registration its own extra slot.
        // IdentityHashMap is intentional: two registrations may wrap the same
        // callback object but must still not share state.
        private val callbackExtras = IdentityHashMap<PrioritizedCallback, Any?>()
        private var activeCallback: PrioritizedCallback? = null

        override var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                resultSet = true
                throwableValue = null
                // Match MethodHookParam.setResult(): setting null is still an
                // intentional replacement and must skip the original method.
                earlyReturn = true
            }

        override var throwable: Throwable?
            get() = throwableValue
            set(value) {
                throwableValue = value
                // Match MethodHookParam.setThrowable(), including its unusual
                // but documented setThrowable(null) behavior: clear any prior
                // result and request an early null return.
                resultValue = null
                resultSet = false
                earlyReturn = true
            }
        override var extra: Any?
            get() = activeCallback?.let { callbackExtras[it] }
            set(value) {
                // The compat wrappers expose extra only while a callback is
                // executing. Match that behavior outside a callback with a
                // harmless null read/no-op write.
                activeCallback?.let { callbackExtras[it] = value }
            }
        var earlyReturn = false

        fun <T> withCallback(callback: PrioritizedCallback, block: () -> T): T {
            val previous = activeCallback
            activeCallback = callback
            return try {
                block()
            } finally {
                activeCallback = previous
            }
        }

        fun clearCallbackState() {
            activeCallback = null
            callbackExtras.clear()
        }

        /** State XposedBridge restores when one callback throws. */
        data class State(
            val result: Any?,
            val throwable: Throwable?,
            val resultSet: Boolean,
            val earlyReturn: Boolean,
        )

        fun snapshot(): State = State(resultValue, throwableValue, resultSet, earlyReturn)

        fun restore(state: State) {
            resultValue = state.result
            throwableValue = state.throwable
            resultSet = state.resultSet
            earlyReturn = state.earlyReturn
        }

        /**
         * XposedBridge treats an exception thrown by beforeHookedMethod as a callback
         * failure, not as a requested replacement result.  Clear the callback's
         * unfinished result/throwable and allow the original method to proceed.
         */
        fun resetAfterBeforeFailure() {
            resultValue = null
            throwableValue = null
            resultSet = false
            earlyReturn = false
        }
    }

    // Successful entries and their backups live until process exit. Callback
    // snapshots may still be executing after the last handle logically unhooks.
    private val hooks: ConcurrentHashMap<Member, HookEntry> = ConcurrentHashMap()

    /** Returns the existing owner, or null when this entry owns installation. */
    fun register(entry: HookEntry): HookEntry? = hooks.putIfAbsent(entry.member, entry)

    fun unregisterFailed(entry: HookEntry) {
        hooks.remove(entry.member, entry)
    }

    fun getEntry(member: Member): HookEntry? = hooks[member]

    fun hookedMembers(): Set<Member> = hooks.values
        .filter { it.isInstalled && it.callbacks.isNotEmpty() }
        .mapTo(linkedSetOf()) { it.member }

    private fun dispatch(entry: HookEntry, thisObj: Any?, args: Array<Any?>): Any? {
        // A retained LSPlant hook with no business callbacks is an origin call.
        // CopyOnWriteArrayList.toArray() takes one atomic backing-array snapshot.
        // Kotlin toList() can read size and element separately for a singleton.
        val snapshot = entry.callbacks.toTypedArray()
        if (snapshot.isEmpty()) return entry.invokeOriginal(thisObj, args)
        val param = MutableHookParam(entry.member, thisObj, args)

        var beforeCount = 0
        for (pc in snapshot) {
            if (param.earlyReturn) break
            try {
                param.withCallback(pc) { pc.callback.beforeHookedMember(param) }
            } catch (t: Throwable) {
                // Callback failures are logged; a callback must set throwable
                // when it intentionally wants the host invocation to fail.
                WeLogger.e(TAG, "before callback failed for ${entry.member}", t)
                param.resetAfterBeforeFailure()
            }
            beforeCount++
        }

        if (!param.earlyReturn) {
            try {
                param.result = entry.invokeOriginal(thisObj, param.args)
            } catch (t: Throwable) {
                param.throwable = t
            }
        }

        for (index in beforeCount - 1 downTo 0) {
            val pc = snapshot[index]
            val beforeAfter = param.snapshot()
            try {
                param.withCallback(pc) { pc.callback.afterHookedMember(param) }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "after callback failed for ${entry.member}", t)
                param.restore(beforeAfter)
            }
        }

        val finalThrowable = param.throwable
        val finalResult = param.result
        param.clearCallbackState()
        finalThrowable?.let { throw it }
        return finalResult
    }

    fun invokeOriginal(member: Member, thisObj: Any?, args: Array<Any?>): Any? {
        val entry = hooks[member]
            ?: throw IllegalArgumentException("$TAG: member is not hooked: $member")
        return entry.invokeOriginal(thisObj, args)
    }
}
