package moe.ouom.wekit.loader.modern

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import moe.ouom.wekit.loader.hookapi.IHookBridge
import moe.ouom.wekit.loader.modern.codegen.Lsp100ProxyClassMaker
import moe.ouom.wekit.loader.modern.dyn.Lsp100CallbackProxy
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Lsp100HookWrapper {

    private val EMPTY_CALLBACKS = arrayOfNulls<CallbackWrapper>(0).filterNotNull().toTypedArray()
    private val sNextHookId = AtomicLong(1)
    private val sRegistryWriteLock = Any()
    private val DEFAULT_PROXY: Class<*> = Lsp100CallbackProxy.P0000000050::class.java
    private const val DEFAULT_PRIORITY = 50
    private val sCallbackRegistry = ConcurrentHashMap<Int, ConcurrentHashMap<Class<*>, ConcurrentHashMap<Member, CallbackListHolder>>>()

    @JvmField
    var self: XposedModule? = null

    @Suppress("UNCHECKED_CAST")
    fun hookAndRegisterMethodCallback(
        method: Member,
        callback: IHookBridge.IMemberHookCallback,
        priority: Int
    ): UnhookHandle {
        val (proxyClass, tag) = try {
            val c = generateProxyClassForCallback(priority)
            c to priority
        } catch (e: RuntimeException) {
            WeLogger.w("failed to generate proxy class, fallback to default", e)
            DEFAULT_PROXY to DEFAULT_PRIORITY
        }

        val wrapper = CallbackWrapper(callback, priority, tag)
        val handle = UnhookHandle(wrapper, method)
        val declaringClass = method.declaringClass

        val holder: CallbackListHolder
        synchronized(sRegistryWriteLock) {
            val taggedCallbackRegistry = sCallbackRegistry.getOrPut(tag) { ConcurrentHashMap() }
            val callbackList = taggedCallbackRegistry.getOrPut(declaringClass) { ConcurrentHashMap() }
            var h = callbackList[method]
            if (h == null) {
                when (method) {
                    is Method -> self!!.hook(method, tag, proxyClass as Class<out XposedInterface.Hooker>)
                    is Constructor<*> -> self!!.hook(method, tag, proxyClass as Class<out XposedInterface.Hooker>)
                    else -> error("only method and constructor can be hooked, but got $method")
                }
                h = CallbackListHolder()
                callbackList[method] = h
            }
            holder = h
        }

        synchronized(holder.lock) {
            val newSize = if (holder.callbacks.isEmpty()) 1 else holder.callbacks.size + 1
            val newCallbacks = arrayOfNulls<CallbackWrapper>(newSize)
            if (holder.callbacks.isNotEmpty()) {
                var i = 0
                while (i < holder.callbacks.size) {
                    if (holder.callbacks[i].priority > priority) {
                        newCallbacks[i] = holder.callbacks[i]
                        i++
                    } else break
                }
                newCallbacks[i] = wrapper
                while (i < holder.callbacks.size) {
                    newCallbacks[i + 1] = holder.callbacks[i]
                    i++
                }
            } else {
                newCallbacks[0] = wrapper
            }
            @Suppress("UNCHECKED_CAST")
            holder.callbacks = newCallbacks as Array<CallbackWrapper>
        }
        return handle
    }

    fun removeMethodCallback(method: Member, callback: CallbackWrapper) {
        val taggedCallbackRegistry = sCallbackRegistry[callback.tag] ?: return
        val callbackList = taggedCallbackRegistry[method.declaringClass] ?: return
        val holder = callbackList[method] ?: return
        synchronized(holder.lock) {
            holder.callbacks = holder.callbacks.filter { it !== callback }.toTypedArray()
        }
    }

    fun isMethodCallbackRegistered(method: Member, callback: CallbackWrapper): Boolean {
        val taggedCallbackRegistry = sCallbackRegistry[callback.tag] ?: return false
        val callbackList = taggedCallbackRegistry[method.declaringClass] ?: return false
        val holder = callbackList[method] ?: return false
        return holder.callbacks.any { it === callback }
    }

    private fun copyCallbacks(holder: CallbackListHolder?): Array<CallbackWrapper> {
        holder ?: return EMPTY_CALLBACKS
        synchronized(holder.lock) {
            return if (holder.callbacks.isNotEmpty()) holder.callbacks.clone() else EMPTY_CALLBACKS
        }
    }

    private fun generateProxyClassForCallback(priority: Int): Class<*> =
        Lsp100ProxyClassMaker.createProxyClass(priority)

    fun getHookCounter(): Int = (sNextHookId.get() - 1).toInt()

    class CallbackWrapper(
        val callback: IHookBridge.IMemberHookCallback,
        val priority: Int,
        val tag: Int
    )

    class CallbackListHolder {
        val lock = Any()
        // sorted by priority, descending
        var callbacks: Array<CallbackWrapper> = emptyArray()
    }

    class InvocationParamWrapper : IHookBridge.IMemberHookParam {
        var index: Int = -1
        var isAfter: Boolean = false
        var callbacks: Array<CallbackWrapper> = emptyArray()
        var extras: Array<Any?>? = null
        var before: XposedInterface.BeforeHookCallback? = null
        var after: XposedInterface.AfterHookCallback? = null

        override fun getMember(): Member {
            checkLifecycle()
            return if (isAfter) after!!.member else before!!.member
        }

        override fun getThisObject(): Any? {
            checkLifecycle()
            return if (isAfter) after!!.thisObject else before!!.thisObject
        }

        override fun getArgs(): Array<Any?> {
            checkLifecycle()
            return if (isAfter) after!!.args else before!!.args
        }

        override fun getResult(): Any? {
            checkLifecycle()
            return if (isAfter) after!!.result else null
        }

        override fun setResult(result: Any?) {
            checkLifecycle()
            if (isAfter) after!!.result = result else before!!.returnAndSkip(result)
        }

        override fun getThrowable(): Throwable? {
            checkLifecycle()
            return if (isAfter) after!!.throwable else null
        }

        override fun setThrowable(throwable: Throwable) {
            checkLifecycle()
            if (isAfter) after!!.throwable = throwable else before!!.throwAndSkip(throwable)
        }

        override fun getExtra(): Any? {
            checkLifecycle()
            return extras?.get(index)
        }

        override fun setExtra(extra: Any?) {
            checkLifecycle()
            if (extras == null) extras = arrayOfNulls(callbacks.size)
            extras!![index] = extra
        }

        private fun checkLifecycle() {
            if ((isAfter && after == null) || (!isAfter && before == null)) {
                error("attempt to access hook param after destroyed")
            }
        }
    }

    object Lsp100HookAgent : XposedInterface.Hooker {

        fun handleBeforeHookedMethod(
            callback: XposedInterface.BeforeHookCallback,
            tag: Int
        ): InvocationParamWrapper? {
            val taggedCallbackRegistry = sCallbackRegistry[tag] ?: return null
            val member = callback.member
            val callbackList = taggedCallbackRegistry[member.declaringClass] ?: return null
            val holder = callbackList[member] ?: return null
            val callbacks = copyCallbacks(holder)
            if (callbacks.isEmpty()) return null

            val param = InvocationParamWrapper().apply {
                this.callbacks = callbacks
                this.before = callback
                this.isAfter = false
            }
            for (i in callbacks.indices) {
                param.index = i
                try {
                    callbacks[i].callback.beforeHookedMember(param)
                } catch (t: Throwable) {
                    self!!.log(t.toString(), t)
                }
            }
            param.index = -1
            return param
        }

        fun handleAfterHookedMethod(
            callback: XposedInterface.AfterHookCallback,
            param: InvocationParamWrapper?
        ) {
            checkNotNull(param) { "param is null" }
            param.isAfter = true
            param.after = callback
            for (i in param.callbacks.indices.reversed()) {
                param.index = i
                try {
                    param.callbacks[i].callback.afterHookedMember(param)
                } catch (t: Throwable) {
                    self!!.log(t.toString(), t)
                }
            }
            param.callbacks = emptyArray()
            param.extras = null
            param.before = null
            param.after = null
        }
    }

    class UnhookHandle(
        private val callback: CallbackWrapper,
        private val method: Member
    ) : IHookBridge.MemberUnhookHandle {
        override fun getMember(): Member = method
        override fun getCallback(): IHookBridge.IMemberHookCallback = callback.callback
        override fun isHookActive(): Boolean = isMethodCallbackRegistered(method, callback)
        override fun unhook() = removeMethodCallback(method, callback)
    }
}
