package moe.ouom.wekit.core.model

import androidx.annotation.Keep
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.hooks.core.factory._ExceptionFactory
import moe.ouom.wekit.loader.startup.HybridClassLoader
import moe.ouom.wekit.util.log.WeLogger
import java.lang.reflect.Member

/**
 * 所有 hook 功能的基础类, 都应该要继承这个类
 * 2026.1.22：重构为 Kotlin 以支持 DSL 语法
 */
@Keep
abstract class BaseHookItem {

    /**
     * 功能名称/路径
     */
    var path: String = ""
        private set

    /**
     * 功能描述
     */
    var desc: String = ""
        private set

    /**
     * 是否已加载
     */
    var isLoad: Boolean = false
        private set

    /**
     * 获取简单类名
     */
    val simpleName: String
        get() = this::class.java.simpleName

    /**
     * 获取功能项名称（路径的最后一部分）
     */
    val itemName: String
        get() {
            val index = path.lastIndexOf("/")
            return if (index == -1) path else path.substring(index + 1)
        }

    /**
     * 设置路径
     */
    fun setPath(path: String) {
        this.path = path
    }

    /**
     * 设置描述
     */
    fun setDesc(desc: String) {
        this.desc = desc
    }

    /**
     * 开始加载 Hook
     */
    fun startLoad() {
        val config = ConfigManager.getDefaultConfig()
        val verboseLog = config.getBooleanOrFalse(Constants.PrekVerboseLog)

        if (verboseLog) {
            WeLogger.d("BaseHookItem.startLoad() called for ${this::class.java.simpleName}, isLoad=$isLoad")
        }

        if (isLoad) {
            if (verboseLog) {
                WeLogger.w("BaseHookItem.startLoad() skipped - already loaded for ${this::class.java.simpleName}")
            }
            return
        }
        try {
            isLoad = true
            if (verboseLog) {
                WeLogger.d("BaseHookItem.startLoad() calling initOnce() for ${this::class.java.simpleName}")
            }

            if (initOnce()) {
                if (verboseLog) {
                    WeLogger.d("BaseHookItem.startLoad() calling entry() for ${this::class.java.simpleName}")
                }
                entry(HybridClassLoader.getHostClassLoader())
            } else {
                if (verboseLog) {
                    WeLogger.w("BaseHookItem.startLoad() initOnce() returned false for ${this::class.java.simpleName}")
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("BaseHookItem Load Failed", e)
            _ExceptionFactory.add(this, e)
        }
    }

    /**
     * 在 loadHook 前执行一次
     * 返回 true 表示继续执行 loadHook
     * 返回 false 表示由 initOnce 自行处理 loadHook 事件
     */
    open fun initOnce(): Boolean = true

    /**
     * Hook 入口方法
     */
    abstract fun entry(classLoader: ClassLoader)

    /**
     * 卸载 Hook
     */
    open fun unload(classLoader: ClassLoader) {
        val config = ConfigManager.getDefaultConfig()
        val verboseLog = config.getBooleanOrFalse(Constants.PrekVerboseLog)

        if (verboseLog) {
            WeLogger.d("BaseHookItem.unload() called for ${this::class.java.simpleName}, isLoad=$isLoad")
        }
        isLoad = false
        if (verboseLog) {
            WeLogger.d("BaseHookItem.unload() completed for ${this::class.java.simpleName}, isLoad=$isLoad")
        }
    }

    /**
     * 标准 hook 方法执行前
     */
    protected fun hookBefore(method: Member, action: HookAction): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }


    /**
     * 标准 hook 方法执行后
     */
    protected fun hookAfter(method: Member, action: HookAction): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 标准 hook 构造方法执行前
     */
    protected fun hookBefore(
        clazz: Class<*>,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 标准 hook 构造方法执行后
     */
    protected fun hookAfter(
        clazz: Class<*>,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader, parameterTypesAndCallback)
        )

        return XposedBridge.hookMethod(
            m,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
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
    protected fun hookBefore(
        clazz: Class<*>,
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 适配 hookAfter(Class, MethodName, Action)
     * 自动 Hook 该类下所有同名的方法
     */
    protected fun hookAfter(
        clazz: Class<*>,
        methodName: String,
        action: HookAction
    ): Set<XC_MethodHook.Unhook> {
        return XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object : XC_MethodHook(ConfigManager.dGetInt(Constants.PrekCfgXXX + "wekit_hook_priority", 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tryExecute(param, action)
                }
            }
        )
    }

    /**
     * 带优先级的版本 (Before)
     */
    protected fun hookBefore(
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
    protected fun hookAfter(
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
    protected fun hookBefore(method: Member, priority: Int, action: HookAction): XC_MethodHook.Unhook {
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
    protected fun hookAfter(method: Member, priority: Int, action: HookAction): XC_MethodHook.Unhook {
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
    protected fun hookBefore(
        clazz: Class<*>,
        priority: Int,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader, parameterTypesAndCallback)
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
    protected fun hookAfter(
        clazz: Class<*>,
        priority: Int,
        action: HookAction,
        vararg parameterTypesAndCallback: Any
    ): XC_MethodHook.Unhook {
        val m = XposedHelpers.findConstructorExact(
            clazz,
            *getParameterClasses(clazz.classLoader, parameterTypesAndCallback)
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
    protected open fun tryExecute(param: XC_MethodHook.MethodHookParam, hookAction: HookAction) {
        if (isLoad) {
            try {
                hookAction.call(param)
            } catch (throwable: Throwable) {
                _ExceptionFactory.add(this, throwable)
            }
        }
    }

    /**
     * Hook 动作接口
     */
    protected fun interface HookAction {
        @Throws(Throwable::class)
        fun call(param: XC_MethodHook.MethodHookParam)
    }

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
                    is String -> XposedHelpers.findClass(type, classLoader)
                    else -> throw IllegalArgumentException("parameter type must either be specified as Class or String")
                }
            }

            // if there are no arguments for the method
            return parameterClasses ?: emptyArray()
        }
    }
}
