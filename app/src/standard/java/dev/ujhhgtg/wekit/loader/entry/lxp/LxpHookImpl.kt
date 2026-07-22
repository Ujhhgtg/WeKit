package dev.ujhhgtg.wekit.loader.entry.lxp

import android.util.Log
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.loader.abc.IClassLoaderHelper
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback
import dev.ujhhgtg.wekit.loader.abc.IHookBridge.MemberUnhookHandle
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

object LxpHookImpl : IHookBridge, ILoaderService {

    lateinit var self: XposedModule
    private const val TAG = "LxpHookImpl"

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val loaderName: String get() = "libxposed API ${self.apiVersion}"
    override val hookBridgeName: String get() = loaderName
    override val apiLevel: Int get() = self.apiVersion
    override val frameworkName: String get() = self.frameworkName
    override val frameworkVersion: String get() = self.frameworkVersion
    override val frameworkVersionCode: Long get() = self.frameworkVersionCode
    override val hookCounter: Long get() = LxpHookWrapper.hookCounter.toLong()
    override val hookedMethods: Set<Member?> get() = LxpHookWrapper.hookedMethodsRaw
    override val entryPointName: String = "dev.ujhhgtg.wekit.loader.entry.lxp.LxpHookImpl"
    override val loaderVersionCode: Int = BuildConfig.VERSION_CODE
    override val loaderVersionName: String = BuildConfig.VERSION_NAME
    override val mainModulePath: String get() = self.getModuleApplicationInfo().sourceDir

    override fun hookMethod(
        member: Member,
        callback: IMemberHookCallback,
        priority: Int
    ): MemberUnhookHandle {
        return LxpHookWrapper.hookAndRegisterMethodCallback(member, callback, priority)
    }

    override val isDeoptimizationSupported: Boolean = true

    override fun deoptimize(executable: Executable): Boolean {
        return self.deoptimize(executable)
    }

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? {
        val invoker: XposedInterface.Invoker<*, Method?> = self.getInvoker(method)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.invoke(thisObject, *args)
    }

    override fun <T> invokeOriginalConstructor(
        ctor: Constructor<T?>,
        thisObject: T,
        args: Array<Any?>
    ) {
        val invoker: CtorInvoker<T?> = self.getInvoker<T?>(ctor)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        // invoke constructor as method, s.t. <init>(args...)V
        invoker.invoke(thisObject, *args)
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        val invoker = self.getInvoker(constructor)
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
        return invoker.newInstance(*args)
    }

    override fun log(msg: String) {
        self.log(Log.INFO, TAG, msg, null)
    }

    override fun log(tr: Throwable) {
        val msg = tr.message ?: tr.javaClass.simpleName
        self.log(Log.ERROR, TAG, msg, tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? {
        return LxpExtCmd.handleQueryExtension(key)
    }

    fun init(base: XposedModule) {
        self = base
        LxpHookWrapper.self = base
    }
}
