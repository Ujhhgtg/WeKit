package moe.ouom.wekit.core.dsl

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.hooks.core.factory._ExceptionFactory
import moe.ouom.wekit.utils.log.WeLogger
import java.lang.reflect.Method

/**
 * Dex 方法 Hook 构建器
 */
class DexMethodHookBuilder(
    private val method: Method,
    private val priority: Int?,
    private val hookItem: Any? = null  // 可选的 HookItem 实例，用于检查启用状态
) {
    private var beforeAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var afterAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var replaceAction: ((XC_MethodHook.MethodHookParam) -> Any?)? = null
    private var checkEnabled: Boolean = false  // 标记是否需要检查启用状态

    /**
     * DSL: 配置 Hook 行为
     */
    fun hook(block: HookConfigBuilder.() -> Unit) {
        val builder = HookConfigBuilder()
        builder.block()

        this.beforeAction = builder.beforeAction
        this.afterAction = builder.afterAction
        this.replaceAction = builder.replaceAction
        this.checkEnabled = builder.checkEnabled
    }

    /**
     * 检查功能是否启用
     */
    private fun isEnabled(): Boolean {
        if (!checkEnabled) return true  // 如果不需要检查，默认启用

        // 尝试将 hookItem 转换为 BaseSwitchFunctionHookItem 并检查状态
        return try {
            val hookItemClass = hookItem?.javaClass
            val isEnabledMethod = hookItemClass?.getMethod("isEnabled")
            isEnabledMethod?.invoke(hookItem) as? Boolean ?: true
        } catch (e: Exception) {
            WeLogger.w("Failed to check enabled status, defaulting to true", e)
            true
        }
    }

    /**
     * 执行 Hook
     */
    fun execute() {
        val p = priority ?: WeConfig.dGetInt(
            "${Constants.PrekCfgXXX}wekit_hook_priority",
            50
        )


        XposedBridge.hookMethod(method, object : XC_MethodHook(p) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // 检查功能是否启用
                    if (!isEnabled()) {
                        return
                    }

                    // 如果有 replace 动作，在 before 中执行
                    replaceAction?.let {
                        param.result = it(param)
                    }

                    // 执行 before 动作
                    beforeAction?.invoke(param)
                } catch (e: Throwable) {
                    WeLogger.e(e)
                    _ExceptionFactory.add(null, e)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // 检查功能是否启用
                    if (!isEnabled()) {
                        return
                    }

                    afterAction?.invoke(param)
                } catch (e: Throwable) {
                    WeLogger.e(e)
                    _ExceptionFactory.add(null, e)
                }
            }
        })
    }

    /**
     * Hook 配置构建器
     */
    class HookConfigBuilder {
        internal var beforeAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
        internal var afterAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
        internal var replaceAction: ((XC_MethodHook.MethodHookParam) -> Any?)? = null
        internal var checkEnabled: Boolean = false  // 标记是否需要检查启用状态

        /**
         * 在方法执行前 Hook（仅当功能启用时）
         */
        fun beforeIfEnabled(action: (XC_MethodHook.MethodHookParam) -> Unit) {
            beforeAction = action
            checkEnabled = true
        }

        /**
         * 在方法执行后 Hook（仅当功能启用时）
         */
        fun afterIfEnabled(action: (XC_MethodHook.MethodHookParam) -> Unit) {
            afterAction = action
            checkEnabled = true
        }

        /**
         * 替换方法返回值
         */
        fun replace(action: (XC_MethodHook.MethodHookParam) -> Any?) {
            replaceAction = action
        }
    }
}

/**
 * 扩展函数：将结果设置为 null
 */
fun XC_MethodHook.MethodHookParam.resultNull() {
    this.result = null
}

/**
 * 扩展函数：将结果设置为指定值
 */
fun XC_MethodHook.MethodHookParam.resultValue(value: Any?) {
    this.result = value
}
