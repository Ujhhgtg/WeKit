package moe.ouom.wekit.core.model

import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.hooks.utils.ExceptionFactory
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.Member
import kotlin.reflect.KClass

abstract class BaseHookItem {

    var path: String = ""

    var description: String = ""

    var hasEnabled: Boolean = false
        private set

    val itemName: String
        get() {
            val index = path.lastIndexOf("/")
            return if (index == -1) path else path.substring(index + 1)
        }

    fun enable() {
        if (hasEnabled) return
        runCatching {
            hasEnabled = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e("failed to load item", e)
            ExceptionFactory.add(this, e)
        }
    }

    fun disable() {
        if (!hasEnabled) return
        runCatching {
            hasEnabled = false
            onDisable()
        }.onFailure { e -> WeLogger.e("failed to unload item", e) }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    fun hookBefore(method: Member, action: HookAction): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    @JvmName("hookBeforeExt2")
    fun Member.hookBefore(
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookBefore(this, action)
    }

    @JvmName("hookBeforeKavaRef")
    fun <T : Any> hookBefore(
        methodResolver: MethodResolver<Class<T>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookBefore(methodResolver.self, action)
    }

    @JvmName("hookBeforeKavaRefExt")
    fun <T : Any> MethodResolver<Class<T>>.hookBefore(action: HookAction): XC_MethodHook.Unhook {
        return hookBefore(this, action)
    }

    @JvmName("hookBeforeKavaRef2")
    fun <T : Any> hookBefore(
        methodResolver: MethodResolver<KClass<T>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookBefore(methodResolver.self, action)
    }

    @JvmName("hookBeforeKavaRefExt2")
    fun <T : Any> MethodResolver<KClass<T>>.hookBefore(action: HookAction): XC_MethodHook.Unhook {
        return hookBefore(this, action)
    }

    @JvmName("hookBeforeKavaRef3")
    fun hookBefore(
        methodResolver: MethodResolver<Class<*>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookBefore(methodResolver.self, action)
    }

    @JvmName("hookBeforeKavaRefExt3")
    fun MethodResolver<Class<*>>.hookBefore(action: HookAction): XC_MethodHook.Unhook {
        return hookBefore(this, action)
    }

    /**
     * 标准 hook 方法执行后
     */
    fun hookAfter(method: Member, action: HookAction): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    @JvmName("hookAfterExt2")
    fun Member.hookAfter(
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookAfter(this, action)
    }

    @JvmName("hookAfterKavaRef")
    fun <T : Any> hookAfter(
        methodResolver: MethodResolver<Class<T>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookAfter(methodResolver.self, action)
    }

    @JvmName("hookAfterKavaRefExt")
    fun <T : Any> MethodResolver<Class<T>>.hookAfter(action: HookAction): XC_MethodHook.Unhook {
        return hookAfter(this, action)
    }

    @JvmName("hookAfterKavaRef2")
    fun <T : Any> hookAfter(
        methodResolver: MethodResolver<KClass<T>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookAfter(methodResolver.self, action)
    }

    @JvmName("hookAfterKavaRefExt2")
    fun <T : Any> MethodResolver<KClass<T>>.hookAfter(action: HookAction): XC_MethodHook.Unhook {
        return hookAfter(this, action)
    }

    @JvmName("hookAfterKavaRef3")
    fun hookAfter(
        methodResolver: MethodResolver<Class<*>>,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return hookAfter(methodResolver.self, action)
    }

    @JvmName("hookAfterKavaRefExt3")
    fun MethodResolver<Class<*>>.hookAfter(action: HookAction): XC_MethodHook.Unhook {
        return hookAfter(this, action)
    }

    /**
     * 标准 hook 构造方法执行前
     */
    fun hookBefore(
        clazz: Class<*>,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader!!, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 标准 hook 构造方法执行后
     */
    fun hookAfter(
        clazz: Class<*>,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader!!, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 适配 hookBefore(Class, MethodName, Action)
     * 自动 Hook 该类下所有同名的方法
     */
    fun hookBefore(
        clazz: Class<*>,
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    @JvmName("hookBeforeExt")
    fun Class<*>.hookBefore(
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return hookBefore(this, methodName, action)
    }

    /**
     * 适配 hookAfter(Class, MethodName, Action)
     * 自动 Hook 该类下所有同名的方法
     */
    fun hookAfter(
        clazz: Class<*>,
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    @JvmName("hookAfterExt")
    fun Class<*>.hookAfter(
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return hookAfter(this, methodName, action)
    }

    /**
     * 带优先级的版本 (Before)
     */
    fun hookBefore(
        clazz: Class<*>,
        methodName: String,
        priority: Int,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object : XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带优先级的版本 (After)
     */
    fun hookAfter(
        clazz: Class<*>,
        methodName: String,
        priority: Int,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object : XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带执行优先级的 hook (before)
     */
    fun hookBefore(
        method: Member,
        priority: Int,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带执行优先级的 hook (after)
     */
    fun hookAfter(
        method: Member,
        priority: Int,
        action: HookAction
    ): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带执行优先级的 hook 构造方法执行前
     */
    fun hookBefore(
        clazz: Class<*>,
        priority: Int,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader!!, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object : XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带执行优先级的 hook 构造方法执行后
     */
    fun hookAfter(
        clazz: Class<*>,
        priority: Int,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader!!, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object : XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 真正执行接口方法的地方，这么写可以很便捷的捕获异常和子类重写
     */
    open fun tryExecute(param: XC_MethodHook.MethodHookParam, hookAction: HookAction) {
        if (hasEnabled) {
            try {
                hookAction(param)
            } catch (throwable: Throwable) {
                ExceptionFactory.add(this, throwable)
            }
        }
    }

    /**
     * Hook 动作接口
     */
    typealias HookAction = (param: XC_MethodHook.MethodHookParam) -> Unit

    companion object {
        private fun getParameterClasses(
            classLoader: ClassLoader,
            parameterTypesAndCallback: Array<out Any>
        ): Array<Class<*>> {
            var parameterClasses: Array<Class<*>>? = null

            for (i in parameterTypesAndCallback.indices.reversed()) {
                val type = parameterTypesAndCallback[i]

                // ignore trailing callback
                if (type is XC_MethodHook) continue

                if (parameterClasses == null) {
                    parameterClasses = Array(i + 1) { Any::class.java }
                }

                parameterClasses[i] = when (type) {
                    is Class<*> -> type
                    is String -> type.toClass(classLoader)
                    else -> throw IllegalArgumentException("parameter type must either be specified as Class or String")
                }
            }

            // if there are no arguments for the method
            return parameterClasses ?: emptyArray()
        }
    }
}
