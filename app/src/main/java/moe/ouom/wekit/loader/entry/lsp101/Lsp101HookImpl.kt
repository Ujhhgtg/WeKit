package moe.ouom.wekit.loader.entry.lsp101

import android.util.Log
import dev.ujhhgtg.nameof.nameof
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedModule
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.loader.abc.IClassLoaderHelper
import moe.ouom.wekit.loader.abc.IHookBridge
import moe.ouom.wekit.loader.abc.IHookBridge.IMemberHookCallback
import moe.ouom.wekit.loader.abc.IHookBridge.MemberUnhookHandle
import moe.ouom.wekit.loader.abc.ILoaderService
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

class Lsp101HookImpl private constructor() : IHookBridge, ILoaderService {

    private var mClassLoaderHelper: IClassLoaderHelper? = null

    override val apiLevel: Int = self!!.getApiVersion()

    override val frameworkName: String = self!!.getFrameworkName()

    override val frameworkVersion: String = self!!.getFrameworkVersion()

    override val frameworkVersionCode: Long = self!!.getFrameworkVersionCode()

    override fun hookMethod(
        member: Member,
        callback: IMemberHookCallback,
        priority: Int
    ): MemberUnhookHandle {
        return Lsp101HookWrapper.hookAndRegisterMethodCallback(member, callback, priority)
    }

    override val isDeoptimizationSupported: Boolean = true

    override fun deoptimize(member: Member): Boolean {
        return self!!.deoptimize(member as Executable)
    }

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? {
        val invoker: XposedInterface.Invoker<*, Method?> = self!!.getInvoker(method)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.invoke(thisObject, *args)
    }

    override fun <T> invokeOriginalConstructor(
        ctor: Constructor<T?>,
        thisObject: T,
        args: Array<Any?>
    ) {
        val invoker: CtorInvoker<T?> = self!!.getInvoker<T?>(ctor)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        // invoke constructor as method, s.t. <init>(args...)V
        invoker.invoke(thisObject, *args)
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        val invoker = self!!.getInvoker(constructor)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.newInstance(*args)
    }

    override val hookCounter: Long = Lsp101HookWrapper.hookCounter.toLong()

    override val hookedMethods: Set<Member?> = Lsp101HookWrapper.hookedMethodsRaw

    override val entryPointName: String = this.javaClass.getName()

    override val loaderVersionCode: Int = BuildConfig.VERSION_CODE

    override val loaderVersionName: String = BuildConfig.VERSION_NAME

    override val mainModulePath: String = self!!.getModuleApplicationInfo().sourceDir

    override fun log(msg: String) {
        val level = Log.INFO
        self!!.log(level, TAG, msg, null)
    }

    override fun log(tr: Throwable) {
        val level = Log.ERROR
        var msg = tr.message
        if (msg == null) {
            msg = tr.javaClass.getSimpleName()
        }
        self!!.log(level, TAG, msg, tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? {
        @Suppress("UNCHECKED_CAST")
        return Lsp101ExtCmd.handleQueryExtension(key)
    }

    override var classLoaderHelper: IClassLoaderHelper? = mClassLoaderHelper

    companion object {
        val INSTANCE: Lsp101HookImpl = Lsp101HookImpl()
        var self: XposedModule? = null
        private val TAG = nameof(Lsp101HookImpl::class)
        fun init(base: XposedModule) {
            self = base
            Lsp101HookWrapper.self = base
        }
    }
}