package moe.ouom.wekit.core.model

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.core.dsl.DexMethodDelegate
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.HookAction
import moe.ouom.wekit.utils.logging.WeLogger
import java.lang.reflect.Executable

abstract class BaseHookItem {

    var path: String = ""

    var description: String = ""

    var hasEnabled: Boolean = false
        private set

    fun enable() {
        if (hasEnabled) return
        runCatching {
            hasEnabled = true
            onEnable()
        }.onFailure { e -> WeLogger.e("failed to enable item", e) }
    }

    fun disable() {
        if (!hasEnabled) return
        runCatching {
            hasEnabled = false
            onDisable()
        }.onFailure { e -> WeLogger.e("failed to disable item", e) }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    // --- hookBefore ---

    inline fun Executable.hookBefore(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        )
    }

    @JvmName("hookBefore2")
    fun MethodResolver<*>.hookBefore(action: HookAction): XC_MethodHook.Unhook {
        return this.self.hookBefore(action)
    }

    @JvmName("hookBefore3")
    inline fun ConstructorResolver<*>.hookBefore(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return this.self.hookBefore(action)
    }

    // --- end hookBefore ---

    // --- hookAfter ---

    inline fun Executable.hookAfter(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(WePrefs.getIntOrDef(PreferenceKeys.HOOK_PRIORITY, 50)) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        )
    }

    @JvmName("hookAfter2")
    inline fun MethodResolver<*>.hookAfter(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return this.self.hookAfter(action)
    }

    @JvmName("hookAfter3")
    inline fun ConstructorResolver<*>.hookAfter(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return this.self.hookAfter(action)
    }

    // --- end hookAfter ---

    // --- dex delegate ---

    inline fun DexMethodDelegate.hookBefore(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return this.method.hookBefore(action)
    }

    inline fun DexMethodDelegate.hookAfter(
        crossinline action: HookAction
    ): XC_MethodHook.Unhook {
        return this.method.hookAfter(action)
    }

    // --- end dex delegate ---

    inline fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        if (this is SwitchHookItem && !this.isEnabled) return
        if (!hasEnabled) return
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $path", e) }
    }
}
