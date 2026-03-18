package moe.ouom.wekit.loader.entry.lsp100

import dev.ujhhgtg.nameof.nameof
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.XposedApiExact
import moe.ouom.wekit.loader.abc.IHookBridge
import moe.ouom.wekit.loader.entry.lsp100.codegen.Lsp100ProxyClassMaker
import moe.ouom.wekit.loader.entry.lsp100.dyn.Lsp100CallbackProxy
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@XposedApiExact(100)
object Lsp100HookWrapper {

    private val TAG = nameof(Lsp100HookWrapper)

    var self: XposedModule? = null

    private val EMPTY_CALLBACKS = emptyArray<CallbackWrapper>()
    private val DEFAULT_PROXY: Class<*> = Lsp100CallbackProxy.P0000000050::class.java
    private const val DEFAULT_PRIORITY = 50

    private val sNextHookId = AtomicLong(1)
    private val sHookedMethods: MutableSet<Member> = ConcurrentHashMap.newKeySet()
    private val sRegistryWriteLock = Any()

    private val sCallbackRegistry =
        ConcurrentHashMap<Int, ConcurrentHashMap<Class<*>, ConcurrentHashMap<Member, CallbackListHolder>>>()

    class CallbackWrapper(
        val callback: IHookBridge.IMemberHookCallback,
        val priority: Int,
        val tag: Int,
    )

    class CallbackListHolder {
        val lock = Any()
        @Volatile
        var callbacks: Array<CallbackWrapper> = emptyArray()
    }

    fun hookAndRegisterMethodCallback(
        method: Member,
        callback: IHookBridge.IMemberHookCallback,
        priority: Int,
    ): UnhookHandle {
        val (proxyClass, tag) = try {
            generateProxyClassForCallback(priority) to priority
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "failed to generate proxy class, fallback to default", e)
            DEFAULT_PROXY to DEFAULT_PRIORITY
        }

        val wrapper = CallbackWrapper(callback, priority, tag)
        val handle = UnhookHandle(wrapper, method)
        val declaringClass = method.declaringClass

        val holder: CallbackListHolder
        synchronized(sRegistryWriteLock) {
            val taggedRegistry = sCallbackRegistry.getOrPut(tag) { ConcurrentHashMap() }
            val callbackList = taggedRegistry.getOrPut(declaringClass) { ConcurrentHashMap() }
            holder = callbackList.getOrPut(method) {
                @Suppress("UNCHECKED_CAST")
                when (method) {
                    is Method -> self!!.hook(method, tag, proxyClass as Class<out XposedInterface.Hooker>)
                    is Constructor<*> -> self!!.hook(method, tag, proxyClass as Class<out XposedInterface.Hooker>)
                    else -> throw IllegalArgumentException("only method and constructor can be hooked, but got $method")
                }
                sHookedMethods.add(method)
                CallbackListHolder()
            }
        }

        synchronized(holder.lock) {
            val old = holder.callbacks
            val new = arrayOfNulls<CallbackWrapper>(old.size + 1)
            val insertIdx = old.indexOfFirst { it.priority <= priority }.takeIf { it >= 0 } ?: old.size
            old.copyInto(new, 0, 0, insertIdx)
            new[insertIdx] = wrapper
            old.copyInto(new, insertIdx + 1, insertIdx, old.size)
            @Suppress("UNCHECKED_CAST")
            holder.callbacks = new as Array<CallbackWrapper>
        }

        return handle
    }

    fun removeMethodCallback(method: Member, callback: CallbackWrapper) {
        val taggedRegistry = sCallbackRegistry[callback.tag] ?: return
        val callbackList = taggedRegistry[method.declaringClass] ?: return
        val holder = callbackList[method] ?: return
        synchronized(holder.lock) {
            holder.callbacks = holder.callbacks.filter { it !== callback }.toTypedArray()
        }
    }

    fun isMethodCallbackRegistered(method: Member, callback: CallbackWrapper): Boolean {
        val taggedRegistry = sCallbackRegistry[callback.tag] ?: return false
        val callbackList = taggedRegistry[method.declaringClass] ?: return false
        val holder = callbackList[method] ?: return false
        return holder.callbacks.any { it === callback }
    }

    private fun copyCallbacks(holder: CallbackListHolder?): Array<CallbackWrapper> {
        holder ?: return EMPTY_CALLBACKS
        return synchronized(holder.lock) { holder.callbacks.clone() }
    }

    class InvocationParamWrapper : IHookBridge.IMemberHookParam {
        var index: Int = -1
        var isAfter: Boolean = false
        var before: XposedInterface.BeforeHookCallback? = null
        var after: XposedInterface.AfterHookCallback? = null
        var callbacks: Array<CallbackWrapper> = emptyArray()
        var extras: Array<Any?>? = null

        override val member: Member get() = checkLifecycle().run {
            if (isAfter) after!!.member else before!!.member
        }

        override val thisObject: Any? get() = checkLifecycle().run {
            if (isAfter) after!!.thisObject else before!!.thisObject
        }

        override val args: Array<Any?> get() = checkLifecycle().run {
            if (isAfter) after!!.args else before!!.args
        }

        override var result: Any?
            get() = checkLifecycle().run { if (isAfter) after!!.result else null }
            set(value) = checkLifecycle().run {
                if (isAfter) after!!.setResult(value) else before!!.returnAndSkip(value)
            }

        override var throwable: Throwable?
            get() = checkLifecycle().run { if (isAfter) after!!.throwable else null }
            set(value) = checkLifecycle().run {
                requireNotNull(value)
                if (isAfter) after!!.setThrowable(value) else before!!.throwAndSkip(value)
            }

        override var extra: Any?
            get() = checkLifecycle().run { extras?.get(index) }
            set(value) = checkLifecycle().run {
                if (extras == null) extras = arrayOfNulls(callbacks.size)
                extras!![index] = value
            }

        private fun checkLifecycle() {
            if ((isAfter && after == null) || (!isAfter && before == null)) {
                throw IllegalStateException("attempt to access hook param after destroyed")
            }
        }
    }

    object Lsp100HookAgent : XposedInterface.Hooker {

        @JvmStatic
        fun handleBeforeHookedMethod(
            callback: XposedInterface.BeforeHookCallback,
            tag: Int,
        ): InvocationParamWrapper? {
            val taggedRegistry = sCallbackRegistry[tag] ?: return null
            val member = callback.member
            val holder = taggedRegistry[member.declaringClass]?.get(member) ?: return null
            val callbacks = copyCallbacks(holder).takeIf { it.isNotEmpty() } ?: return null

            val param = InvocationParamWrapper().apply {
                this.callbacks = callbacks
                this.before = callback
                this.isAfter = false
            }
            for (i in callbacks.indices) {
                param.index = i
                runCatching { callbacks[i].callback.beforeHookedMember(param) }
                    .onFailure { self!!.log(it.toString(), it) }
            }
            param.index = -1
            return param
        }

        @JvmStatic
        fun handleAfterHookedMethod(
            callback: XposedInterface.AfterHookCallback,
            param: InvocationParamWrapper?,
            tag: Int,
        ) {
            param ?: return
            param.isAfter = true
            param.after = callback
            for (i in param.callbacks.indices.reversed()) {
                param.index = i
                runCatching { param.callbacks[i].callback.afterHookedMember(param) }
                    .onFailure { self!!.log(it.toString(), it) }
            }
            param.callbacks = emptyArray()
            param.extras = null
            param.before = null
            param.after = null
        }
    }

    private fun generateProxyClassForCallback(priority: Int): Class<*> =
        Lsp100ProxyClassMaker.getInstance().createProxyClass(priority)

    class UnhookHandle(
        private val callbackWrapper: CallbackWrapper,
        override val member: Member,
    ) : IHookBridge.MemberUnhookHandle {
        override val callback: IHookBridge.IMemberHookCallback get() = callbackWrapper.callback
        override val isHookActive: Boolean get() = isMethodCallbackRegistered(member, callbackWrapper)
        override fun unhook() = removeMethodCallback(member, callbackWrapper)
    }

    @JvmStatic
    fun getHookCounter(): Long = sNextHookId.get() - 1

    @JvmStatic
    fun getHookedMethodsRaw(): Set<Member> = sHookedMethods
}
