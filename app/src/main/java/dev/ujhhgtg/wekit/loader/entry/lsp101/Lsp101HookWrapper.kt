package dev.ujhhgtg.wekit.loader.entry.lsp101

import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.MemberUnhookHandle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.XposedApiMin
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@XposedApiMin(101)
object Lsp101HookWrapper {

    var self: XposedModule? = null

    private val EMPTY_CALLBACKS = arrayOfNulls<CallbackWrapper>(0).filterNotNull().toTypedArray()

    private val sNextHookId = AtomicLong(1)
    private val sHookedMethods: MutableSet<Member> = ConcurrentHashMap.newKeySet()
    private val sRegistryWriteLock = Any()

    private val sCallbackRegistry =
        ConcurrentHashMap<Int, ConcurrentHashMap<Class<*>, ConcurrentHashMap<Member, CallbackListHolder>>>()

    fun interface Hooker : XposedInterface.Hooker {
        @Throws(Throwable::class)
        fun intercept(chain: XposedInterface.Chain): Any?
    }

    class CallbackWrapper(
        val callback: IMemberHookCallback,
        val priority: Int,
    ) {
        @Suppress("unused")
        val hookId: Long = sNextHookId.getAndIncrement()
    }

    class CallbackListHolder {
        val lock = Any()

        // sorted by priority, descending
        @Volatile
        var callbacks: Array<CallbackWrapper> = emptyArray()
    }

    fun hookAndRegisterMethodCallback(
        method: Member,
        callback: IMemberHookCallback,
        priority: Int,
    ): MemberUnhookHandle {
        val wrapper = CallbackWrapper(callback, priority)
        val handle = UnhookHandle(wrapper, method)
        val declaringClass = method.declaringClass
        val holder: CallbackListHolder
        synchronized(sRegistryWriteLock) {
            val taggedCallbackRegistry = sCallbackRegistry.getOrPut(priority) { ConcurrentHashMap() }
            val callbackList = taggedCallbackRegistry.getOrPut(declaringClass) { ConcurrentHashMap() }
            var h = callbackList[method]
            if (h == null) {
                if (method is Executable) {
                    val agent = Lsp101HookDispatchAgent(priority)
                    val hookHandle = self!!.hook(method)
                        .setPriority(priority)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(agent)
                    agent.setFrameworkHookHandle(hookHandle)
                } else {
                    throw IllegalArgumentException("only method and constructor can be hooked, but got $method")
                }
                h = CallbackListHolder()
                callbackList[method] = h
                sHookedMethods.add(method)
            }
            holder = h
        }
        synchronized(holder.lock) {
            val existing = holder.callbacks
            val newCallbacks = arrayOfNulls<CallbackWrapper>(existing.size + 1)
            var i = 0
            while (i < existing.size && existing[i].priority > priority) {
                newCallbacks[i] = existing[i]
                i++
            }
            newCallbacks[i] = wrapper
            while (i < existing.size) {
                newCallbacks[i + 1] = existing[i]
                i++
            }
            @Suppress("UNCHECKED_CAST")
            holder.callbacks = newCallbacks as Array<CallbackWrapper>
        }
        return handle
    }

    fun removeMethodCallback(method: Member, callback: CallbackWrapper) {
        val taggedCallbackRegistry = sCallbackRegistry[callback.priority] ?: return
        val callbackList = taggedCallbackRegistry[method.declaringClass] ?: return
        val holder = callbackList[method] ?: return
        synchronized(holder.lock) {
            holder.callbacks = holder.callbacks.filter { it !== callback }.toTypedArray()
        }
    }

    fun isMethodCallbackRegistered(method: Member, callback: CallbackWrapper): Boolean {
        val taggedCallbackRegistry = sCallbackRegistry[callback.priority] ?: return false
        val callbackList = taggedCallbackRegistry[method.declaringClass] ?: return false
        val holder = callbackList[method] ?: return false
        return holder.callbacks.any { it === callback }
    }

    private fun copyCallbacks(holder: CallbackListHolder?): Array<CallbackWrapper> {
        holder ?: return EMPTY_CALLBACKS
        synchronized(holder.lock) {
            return holder.callbacks.clone()
        }
    }

    class InvocationParamWrapper : IHookBridge.IMemberHookParam {
        var index: Int = -1
        var isAfter: Boolean = false
        var callbacks: Array<CallbackWrapper>? = null
        var extras: Array<Any?>? = null

        var skipOriginal: Boolean = false
        var thisObjectCompat: Any? = null
        var argsCompat: Array<Any?>? = null
        var memberCompat: Member? = null
        var chain: XposedInterface.Chain? = null

        override val member: Member
            get() {
                checkLifecycle(); return memberCompat!!
            }

        override val thisObject: Any?
            get() {
                checkLifecycle(); return thisObjectCompat
            }

        override val args: Array<Any?>
            get() {
                checkLifecycle(); return argsCompat!!
            }

        override var result: Any? = null
            get() {
                checkLifecycle(); return field
            }
            set(value) {
                checkLifecycle(); field = value; skipOriginal = true
            }

        override var throwable: Throwable? = null
            get() {
                checkLifecycle(); return field
            }
            set(value) {
                checkLifecycle(); field = value; skipOriginal = true
            }

        override var extra: Any?
            get() {
                checkLifecycle()
                val idx = index
                val ex = extras ?: return null
                if (idx < 0 || idx >= ex.size) return null
                return ex[idx]
            }
            set(value) {
                checkLifecycle()
                val cbs = callbacks ?: return
                if (index < 0) return
                val ex = extras ?: arrayOfNulls<Any>(cbs.size).also { extras = it }
                ex[index] = value
            }

        private fun checkLifecycle() {
            if (chain == null) throw IllegalStateException("attempt to access hook param after destroyed")
        }
    }

    internal class Lsp101HookDispatchAgent(private val mPriority: Int) : Hooker {

        private var mHandle: XposedInterface.HookHandle? = null

        fun setFrameworkHookHandle(hookHandle: XposedInterface.HookHandle) {
            check(mHandle == null || mHandle === hookHandle) { "Hook handle already set" }
            mHandle = hookHandle
        }

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val executable = chain.executable
            val taggedCallbackRegistry = sCallbackRegistry[mPriority] ?: return chain.proceed()
            val callbackList = taggedCallbackRegistry[executable.declaringClass] ?: return chain.proceed()
            val holder = callbackList[executable] ?: return chain.proceed()
            val callbacks = copyCallbacks(holder)
            if (callbacks.isEmpty()) return chain.proceed()

            val param = InvocationParamWrapper()
            val argsCompat: Array<Any?> = chain.args.toTypedArray()

            param.memberCompat = executable
            param.thisObjectCompat = chain.thisObject
            param.argsCompat = argsCompat
            param.callbacks = callbacks
            param.chain = chain

            var result: Any? = null
            var throwable: Throwable? = null

            for (i in callbacks.indices) {
                param.index = i
                try {
                    callbacks[i].callback.beforeHookedMember(param)
                } catch (t: Throwable) {
                    Lsp101HookImpl.log(t)
                }
            }
            param.index = -1

            if (!param.skipOriginal) {
                try {
                    result = chain.proceed(argsCompat)
                } catch (t: Throwable) {
                    throwable = t
                }
            } else {
                result = param.result
                throwable = param.throwable
            }

            param.isAfter = true
            param.result = result
            param.throwable = throwable

            for (i in callbacks.indices.reversed()) {
                param.index = i
                try {
                    callbacks[i].callback.afterHookedMember(param)
                } catch (t: Throwable) {
                    Lsp101HookImpl.log(t)
                }
            }

            result = param.result
            throwable = param.throwable

            // for gc
            param.callbacks = null
            param.extras = null
            param.memberCompat = null
            param.thisObjectCompat = null
            param.argsCompat = null
            param.result = null
            param.throwable = null
            param.chain = null

            if (throwable != null) throw throwable
            return result
        }
    }

    class UnhookHandle(
        private val callbackWrapper: CallbackWrapper,
        private val method: Member,
    ) : MemberUnhookHandle {
        override val member: Member = method
        override val callback: IMemberHookCallback = callbackWrapper.callback
        override val isHookActive: Boolean = isMethodCallbackRegistered(method, callbackWrapper)
        override fun unhook() = removeMethodCallback(method, callbackWrapper)
    }

    val hookCounter: Int
        get() = (sNextHookId.get() - 1).toInt()

    val hookedMethodsRaw: Set<Member>
        get() = sHookedMethods
}
