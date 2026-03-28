package dev.ujhhgtg.wekit.loader.entry.lsp100

import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.loader.abc.IClassLoaderHelper
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.XposedApiExact
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

@XposedApiExact(100)
class Lsp100HookImpl private constructor() : IHookBridge, ILoaderService {

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val apiLevel: Int get() = XposedInterface.API
    override val frameworkName: String get() = self!!.frameworkName
    override val frameworkVersion: String get() = self!!.frameworkVersion
    override val frameworkVersionCode: Long get() = self!!.frameworkVersionCode
    override val isDeoptimizationSupported: Boolean get() = true
    override val hookCounter: Long get() = Lsp100HookWrapper.getHookCounter()
    override val hookedMethods: Set<Member> get() = Lsp100HookWrapper.getHookedMethodsRaw()

    override val entryPointName: String get() = nameof(Lsp100HookImpl::class)
    override val loaderVersionName: String get() = BuildConfig.VERSION_NAME
    override val loaderVersionCode: Int get() = BuildConfig.VERSION_CODE
    override val mainModulePath: String get() = self!!.applicationInfo.sourceDir

    override fun hookMethod(member: Member, callback: IHookBridge.IMemberHookCallback, priority: Int): IHookBridge.MemberUnhookHandle =
        Lsp100HookWrapper.hookAndRegisterMethodCallback(member, callback, priority)

    override fun deoptimize(member: Member): Boolean = when (member) {
        is Method -> self!!.deoptimize(member)
        is Constructor<*> -> self!!.deoptimize(member)
        else -> throw IllegalArgumentException("only method and constructor can be deoptimized")
    }

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? {
        val wasAccessible = method.isAccessible
        return try {
            method.isAccessible = true
            self!!.invokeOrigin(method, thisObject, args)
        } finally {
            method.isAccessible = wasAccessible
        }
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        val wasAccessible = constructor.isAccessible
        return try {
            constructor.isAccessible = true
            self!!.newInstanceOrigin(constructor, args)
        } finally {
            constructor.isAccessible = wasAccessible
        }
    }

    override fun <T> invokeOriginalConstructor(ctor: Constructor<T?>, thisObject: T, args: Array<Any?>) {
        val wasAccessible = ctor.isAccessible
        try {
            ctor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            self!!.invokeOrigin(ctor as Constructor<T & Any>, thisObject as (T & Any), args)
        } finally {
            ctor.isAccessible = wasAccessible
        }
    }

    override fun log(msg: String) = self!!.log(msg)
    override fun log(tr: Throwable) = self!!.log(tr.toString(), tr)

    @Suppress("UNCHECKED_CAST")
    override fun queryExtension(key: String, vararg args: Any?): Any? =
        Lsp100ExtCmd.handleQueryExtension(key, args as Array<Any?>?)

    companion object {
        val INSTANCE = Lsp100HookImpl()
        var self: XposedModule? = null
        fun init(base: XposedModule) {
            self = base
            Lsp100HookWrapper.self = base
        }
    }
}
