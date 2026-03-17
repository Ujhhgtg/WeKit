package moe.ouom.wekit.core.dsl

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.ExceptionFactory
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.Executable

class HookBuilder(
    private val executable: Executable,
    private val priority: Int?,
    private val hookItem: Any? = null  // 可选的 HookItem 实例，用于检查启用状态
) {
    private var beforeAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var afterAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var replaceAction: ((XC_MethodHook.MethodHookParam) -> Any?)? = null
    private var checkEnabled: Boolean = false  // 标记是否需要检查启用状态

    fun hook(block: HookConfigBuilder.() -> Unit) {
        val builder = HookConfigBuilder()
        builder.block()

        this.beforeAction = builder.beforeAction
        this.afterAction = builder.afterAction
        this.replaceAction = builder.replaceAction
        this.checkEnabled = builder.checkEnabled
    }

    private fun getIsEnabled(): Boolean {
        if (!checkEnabled) return true  // 如果不需要检查，默认启用

        if (hookItem is ApiHookItem) {
            return true
        }

        if (hookItem is ClickableHookItem) {
            return hookItem.isEnabled
        }

        if (hookItem is SwitchHookItem) {
            return hookItem.isEnabled
        }

        WeLogger.w("failed to check enabled status, defaulting to true")
        return true
    }

    /**
     * 执行 Hook
     */
    fun execute() {
        val prio = priority ?: WePrefs.getIntOrDef(
            PreferenceKeys.HOOK_PRIORITY,
            50
        )

        XposedBridge.hookMethod(executable, object : XC_MethodHook(prio) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // 检查功能是否启用
                    if (!getIsEnabled()) {
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
                    ExceptionFactory.add(null, e)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // 检查功能是否启用
                    if (!getIsEnabled()) {
                        return
                    }

                    afterAction?.invoke(param)
                } catch (e: Throwable) {
                    WeLogger.e(e)
                    ExceptionFactory.add(null, e)
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
