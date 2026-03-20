package dev.ujhhgtg.wekit.core.model

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.core.dsl.DexConstructorDelegate
import dev.ujhhgtg.wekit.core.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HookAction
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import java.lang.reflect.Executable

abstract class BaseHookItem {

    var path: String = ""

    var description: String = ""

    open val targetProcesses = TargetProcesses.PROC_MAIN

    var hasEnabled: Boolean = false
        private set

    fun enable(process: Int = 1) {
        if (hasEnabled) return
        if (process and targetProcesses == 0) return
        runCatching {
            hasEnabled = true
            onEnable()
        }.onFailure { e -> WeLogger.e("failed to enable item", e) }
    }

    fun disable(process: Int = 1) {
        if (!hasEnabled) return
        if (process and targetProcesses == 0) return
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
    ) {
        XposedBridge.hookMethod(
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
    inline fun MethodResolver<*>.hookBefore(
        crossinline action: HookAction
    ) {
        return this.self.hookBefore(action)
    }

    @JvmName("hookBefore3")
    inline fun ConstructorResolver<*>.hookBefore(
        crossinline action: HookAction
    ) {
        return this.self.hookBefore(action)
    }

    // --- end hookBefore ---

    // --- hookAfter ---

    inline fun Executable.hookAfter(
        crossinline action: HookAction
    ) {
        XposedBridge.hookMethod(
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
    ) {
        return this.self.hookAfter(action)
    }

    @JvmName("hookAfter3")
    inline fun ConstructorResolver<*>.hookAfter(
        crossinline action: HookAction
    ) {
        return this.self.hookAfter(action)
    }

    // --- end hookAfter ---

    // --- dex delegate ---

    inline fun DexMethodDelegate.hookBefore(
        crossinline action: HookAction
    ) {
        return this.method.hookBefore(action)
    }

    inline fun DexMethodDelegate.hookAfter(
        crossinline action: HookAction
    ) {
        return this.method.hookAfter(action)
    }

    inline fun DexConstructorDelegate.hookBefore(
        crossinline action: HookAction
    ) {
        return this.constructor.hookBefore(action)
    }

    inline fun DexConstructorDelegate.hookAfter(
        crossinline action: HookAction
    ) {
        return this.constructor.hookAfter(action)
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
