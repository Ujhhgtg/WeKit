package dev.ujhhgtg.wekit.utils

import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Executable

typealias HookAction = XC_MethodHook.MethodHookParam.() -> Unit

// most extension methods are inside BaseHookItem for enabled state checking

inline fun MethodResolver<*>.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return this.self.hookBeforeDirectly(priority, action)
}

inline fun Executable.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return XposedBridge.hookMethod(
        this, object : XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                action(param)
            }
        }
    )
}

inline fun MethodResolver<*>.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return this.self.hookAfterDirectly(priority, action)
}

inline fun Executable.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return XposedBridge.hookMethod(
        this, object : XC_MethodHook(priority) {
            override fun afterHookedMethod(param: MethodHookParam) {
                action(param)
            }
        }
    )
}

fun XC_MethodHook.MethodHookParam.invokeOriginal(thisObject: Any? = null, args: Array<Any?>? = null) {
    XposedBridge.invokeOriginalMethod(this.method, thisObject ?: this.thisObject, args ?: this.args)
}
