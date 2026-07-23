package dev.ujhhgtg.wekit.loader.entry.zygisk

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
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
internal object WekitHookBridgeRuntime {

    private const val TAG = "WekitHookBridgeRuntime"

    // ── Internal types ────────────────────────────────────────────────────────

    internal data class PrioritizedCallback(
        val callback: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback,
        val priority: Int,
    )

    internal class HookEntry(
        val member: Member,
        val backupMethod: Method,   // DexMaker-generated backup; ArtMethod holds original code
    ) {
        val callbacks: CopyOnWriteArrayList<PrioritizedCallback> = CopyOnWriteArrayList()
    }

    // ── Mutable hook param ────────────────────────────────────────────────────

    internal class MutableHookParam(
        override val member: Member,
        override val thisObject: Any?,
        val mutableArgs: Array<Any?>,
    ) : dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam {
        override val args: Array<Any?> get() = mutableArgs
        private var resultValue: Any? = null
        private var throwableValue: Throwable? = null
        internal var resultSet: Boolean = false

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
        override var extra: Any? = null
        internal var earlyReturn = false

        /** State XposedBridge restores when one callback throws. */
        internal data class State(
            val result: Any?,
            val throwable: Throwable?,
            val resultSet: Boolean,
            val earlyReturn: Boolean,
        )

        internal fun snapshot(): State = State(resultValue, throwableValue, resultSet, earlyReturn)

        internal fun restore(state: State) {
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
        internal fun resetAfterBeforeFailure() {
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
    internal val hookLock = Any()

    // ── Registration ──────────────────────────────────────────────────────────

    fun register(member: Member, backup: Method): Long {
        val id = hookIdSeq.incrementAndGet()
        hooks[id] = HookEntry(member, backup)
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

    fun addCallback(hookId: Long, callback: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback, priority: Int) {
        val entry = hooks[hookId] ?: return
        val pc = PrioritizedCallback(callback, priority)
        val list = entry.callbacks
        // CopyOnWriteArrayList.add(index, value) publishes one complete new
        // backing array. Do not rebuild through clear()+addAll(): readers are
        // intentionally lock-free and could otherwise observe an empty list.
        synchronized(list) {
            val idx = list.indexOfFirst { it.priority < priority }
            if (idx == -1) {
                list.add(pc)
            } else {
                list.add(idx, pc)
            }
        }
    }

    fun removeCallback(hookId: Long, callback: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback) {
        hooks[hookId]?.callbacks?.removeIf { it.callback === callback }
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
            ?: throw IllegalStateException("WekitHookBridgeRuntime: no entry for hookId=$hookId")
        val param = MutableHookParam(entry.member, thisObj, args)

        // ── before callbacks ──────────────────────────────────────────────────
        val snapshot = entry.callbacks.toList()
        var beforeCount = 0
        for (pc in snapshot) {
            if (param.earlyReturn) break
            try {
                pc.callback.beforeHookedMember(param)
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
                val result = when (entry.member) {
                    is Constructor<*> -> {
                        backup.invoke(thisObj, *args)
                        null
                    }
                    is Method -> if (java.lang.reflect.Modifier.isStatic(entry.member.modifiers)) {
                        backup.invoke(null, *args)
                    } else {
                        backup.invoke(thisObj, *args)
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
        for (index in (beforeCount - 1) downTo 0) {
            val pc = snapshot[index]
            val beforeAfter = param.snapshot()
            try {
                pc.callback.afterHookedMember(param)
            } catch (t: Throwable) {
                // Xposed restores the value visible on entry to this callback,
                // then continues with the remaining after callbacks.
                WeLogger.e(TAG, "after callback failed for ${entry.member}", t)
                param.restore(beforeAfter)
            }
        }

        param.throwable?.let { throw it }
        return param.result
    }

    // ── Invoke original (from IHookBridge.invokeOriginalMethod) ──────────────

    fun invokeOriginal(member: Member, thisObj: Any?, args: Array<Any?>): Any? {
        val id = memberToHookId[member]
        val backup: Method = (if (id != null) hooks[id]?.backupMethod else null)
            ?: throw IllegalArgumentException(
                "WekitHookBridgeRuntime: member is not hooked: $member"
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
            throw (e.targetException ?: e)
        }
    }
}
