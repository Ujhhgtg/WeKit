package moe.ouom.wekit.utils

import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Executable

typealias HookAction = (param: XC_MethodHook.MethodHookParam) -> Unit

// most extension methods are inside BaseHookItem for enabled state checking

inline fun MethodResolver<*>.hookBeforeDirectly(
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return this.self.hookBeforeDirectly(action)
}

inline fun Executable.hookBeforeDirectly(
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return XposedBridge.hookMethod(
        this, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                action(param)
            }
        }
    )
}

inline fun MethodResolver<*>.hookAfterDirectly(
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return this.self.hookAfterDirectly(action)
}

inline fun Executable.hookAfterDirectly(
    crossinline action: HookAction
): XC_MethodHook.Unhook {
    return XposedBridge.hookMethod(
        this, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                action(param)
            }
        }
    )
}

fun XC_MethodHook.MethodHookParam.invokeOriginal(thisObject: Any? = null, args: Array<Any>? = null) {
    XposedBridge.invokeOriginalMethod(this.method, thisObject ?: this.thisObject, args ?: this.args)
}
