package dev.ujhhgtg.wekit.loader.entry.zygisk

import androidx.annotation.Keep
import dalvik.system.DexFile
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime dispatch engine for ZygiskHookBridge.
 *
 * Stores the per-hook state (member, callbacks, backup method) and implements
 * the before/after callback dispatch contract that matches IHookBridge semantics.
 * Thread-safe: ConcurrentHashMap + CopyOnWriteArrayList.
 */
@Keep
object ArtHookBridgeRuntime {

    private const val TAG = "ArtHookBridgeRuntime"

    // ── Internal types ────────────────────────────────────────────────────────

    /**
     * One concrete callback registration. This deliberately keeps identity
     * equality: two hook handles may wrap the same callback object, and each
     * handle must be able to unhook only its own registration.
     */
    class PrioritizedCallback(
        val callback: IHookBridge.IMemberHookCallback,
        val priority: Int,
    )

    class HookEntry(
        val member: Member,
        val backupMethod: Method,   // DexMaker-generated backup; ArtMethod holds original code
        @Suppress("unused")
        val dexFile: DexFile,
    ) {
        val callbacks: CopyOnWriteArrayList<PrioritizedCallback> = CopyOnWriteArrayList()
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

    // ── State ─────────────────────────────────────────────────────────────────

    private val hooks: ConcurrentHashMap<Long, HookEntry> = ConcurrentHashMap()
    private val memberToHookId: ConcurrentHashMap<Member, Long> = ConcurrentHashMap()
    private val hookIdSeq: AtomicLong = AtomicLong(0L)

    /** Serializes member registration with native ArtMethod replacement. */
    val hookLock = Any()

    // ── Registration ──────────────────────────────────────────────────────────

    fun register(member: Member, backup: Method, dexFile: DexFile): Long {
        val id = hookIdSeq.incrementAndGet()
        hooks[id] = HookEntry(member, backup, dexFile)
        memberToHookId[member] = id
        return id
    }

    fun unregister(hookId: Long) {
        val entry = hooks.remove(hookId) ?: return
        memberToHookId.remove(entry.member)
    }

    /**
     * Remove a successfully unhooked member from the active index while keeping
     * its dispatch state alive. A thread may already have branched into the old
     * native trampoline before unhook suspended ART; that invocation must still
     * be able to resolve its backup after the target ArtMethod is restored.
     */
    fun retire(hookId: Long) {
        val entry = hooks[hookId] ?: return
        memberToHookId.remove(entry.member, hookId)
    }

    fun addCallback(
        hookId: Long,
        callback: IHookBridge.IMemberHookCallback,
        priority: Int,
    ): PrioritizedCallback {
        val entry = requireNotNull(hooks[hookId]) {
            "$TAG: no entry for hookId=$hookId"
        }
        val pc = PrioritizedCallback(callback, priority)
        insertCallback(entry.callbacks, pc)
        return pc
    }

    fun removeCallback(hookId: Long, registration: PrioritizedCallback): Boolean =
        hooks[hookId]?.callbacks?.remove(registration) == true

    /** Restore a registration after a final native unhook attempt fails. */
    fun restoreCallback(hookId: Long, registration: PrioritizedCallback): Boolean {
        val entry = hooks[hookId] ?: return false
        insertCallback(entry.callbacks, registration)
        return true
    }

    private fun insertCallback(
        list: CopyOnWriteArrayList<PrioritizedCallback>,
        callback: PrioritizedCallback,
    ) {
        // CopyOnWriteArrayList.add(index, value) publishes one complete new
        // backing array. Do not rebuild through clear()+addAll(): readers are
        // intentionally lock-free and could otherwise observe an empty list.
        synchronized(list) {
            val idx = list.indexOfFirst { it.priority < callback.priority }
            if (idx == -1) {
                list.add(callback)
            } else {
                list.add(idx, callback)
            }
        }
    }

    fun getEntry(hookId: Long): HookEntry? = hooks[hookId]

    fun getHookId(member: Member): Long? = memberToHookId[member]

    fun hookedMembers(): Set<Member> = memberToHookId.keys.toSet()

    // ── Dispatch (called from DexMaker bridge) ────────────────────────────────

    /**
     * Called by the generated bridge method.
     * @param hookId   the hook registration id
     * @param thisObj  the receiver (null for static methods)
     * @param args     the invocation arguments (may be mutated by callbacks)
     * @return the final result (from callback or from calling backup)
     */
    @Keep
    @JvmStatic
    fun dispatch(hookId: Long, thisObj: Any?, args: Array<Any?>): Any? {
        val entry = hooks[hookId]
            ?: throw IllegalStateException("$TAG: no entry for hookId=$hookId")
        val param = MutableHookParam(entry.member, thisObj, args)

        // ── before callbacks ──────────────────────────────────────────────────
        val snapshot = entry.callbacks.toList()
        var beforeCount = 0
        for (pc in snapshot) {
            if (param.earlyReturn) break
            try {
                param.withCallback(pc) { pc.callback.beforeHookedMember(param) }
            } catch (t: Throwable) {
                // Match XposedBridge: log and swallow callback failures.  A hook
                // callback must use param.throwable when it intentionally wants
                // the host invocation to fail.
                WeLogger.e(TAG, "before callback failed for ${entry.member}", t)
                param.resetAfterBeforeFailure()
            }
            beforeCount++
        }

        // ── call original (via backup) ────────────────────────────────────────
        if (!param.earlyReturn) {
            try {
                val backup = entry.backupMethod
                backup.isAccessible = true
                // IMemberHookParam.args aliases the bridge array. Passing it
                // explicitly documents that before callbacks can mutate the
                // arguments observed by the original implementation.
                val invocationArgs = param.args
                val result = when (entry.member) {
                    is Constructor<*> -> {
                        backup.invoke(thisObj, *invocationArgs)
                        null
                    }
                    is Method -> if (java.lang.reflect.Modifier.isStatic(entry.member.modifiers)) {
                        backup.invoke(null, *invocationArgs)
                    } else {
                        backup.invoke(thisObj, *invocationArgs)
                    }
                    else -> error("unsupported member: ${entry.member}")
                }
                param.result = result
            } catch (e: InvocationTargetException) {
                param.throwable = e.targetException ?: e
            } catch (e: Throwable) {
                param.throwable = e
            }
        }

        // ── after callbacks ───────────────────────────────────────────────────
        for (index in beforeCount - 1 downTo 0) {
            val pc = snapshot[index]
            val beforeAfter = param.snapshot()
            try {
                param.withCallback(pc) { pc.callback.afterHookedMember(param) }
            } catch (t: Throwable) {
                // Xposed restores the value visible on entry to this callback,
                // then continues with the remaining after callbacks.
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

    // ── Invoke original (from IHookBridge.invokeOriginalMethod) ──────────────

    fun invokeOriginal(member: Member, thisObj: Any?, args: Array<Any?>): Any? {
        val id = memberToHookId[member]
        val backup: Method = (if (id != null) hooks[id]?.backupMethod else null)
            ?: throw IllegalArgumentException(
                "$TAG: member is not hooked: $member"
            )
        backup.isAccessible = true
        return try {
            when (member) {
                is Method -> if (java.lang.reflect.Modifier.isStatic(member.modifiers)) {
                    backup.invoke(null, *args)
                } else {
                    backup.invoke(thisObj, *args)
                }
                is Constructor<*> -> {
                    backup.invoke(thisObj, *args)
                    null
                }
                else -> error("unsupported member: $member")
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
