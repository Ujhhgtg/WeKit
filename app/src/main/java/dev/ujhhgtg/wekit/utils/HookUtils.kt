package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import java.lang.reflect.Executable
import java.lang.reflect.Method

typealias HookParam = IHookBridge.IMemberHookParam

typealias HookHandle = IHookBridge.MemberUnhookHandle

typealias HookAction = HookParam.() -> Unit

abstract class HookCallback(val priority: Int = 50) : IHookBridge.IMemberHookCallback {
    protected open fun beforeHookedMethod(param: HookParam) {}

    protected open fun afterHookedMethod(param: HookParam) {}

    override fun beforeHookedMember(param: HookParam) = beforeHookedMethod(param)

    override fun afterHookedMember(param: HookParam) = afterHookedMethod(param)
}

class OriginalMethodInvoker internal constructor(
    private val hookBridge: IHookBridge,
    private val method: Method,
    private val thisObject: Any?,
    private val originalArgs: Array<Any?>
) {
    operator fun invoke(args: Array<Any?>? = null): Any? =
        hookBridge.invokeOriginalMethod(method, thisObject, args ?: originalArgs)
}

private val currentHookBridge: IHookBridge
    get() = checkNotNull(StartupInfo.hookBridge) {
        "hook bridge is unavailable in the current loader"
    }

// most extension methods are inside BaseFeature for enabled state checking

fun BaseReflectedMethod.hookBeforeDirectly(
    priority: Int = 50,
    action: HookAction
) = self.hookBeforeDirectly(priority, action)

fun Executable.hookBeforeDirectly(
    priority: Int = 50,
    action: HookAction
): HookHandle = currentHookBridge.hookMethod(
    this, object : HookCallback(priority) {
        override fun beforeHookedMethod(param: HookParam) {
            action(param)
        }
    }, priority
)

fun BaseReflectedMethod.hookAfterDirectly(
    priority: Int = 50,
    action: HookAction
): HookHandle = self.hookAfterDirectly(priority, action)

fun ReflectedConstructor<*>.hookAfterDirectly(
    priority: Int = 50,
    action: HookAction
): HookHandle = self.hookAfterDirectly(priority, action)

fun Executable.hookAfterDirectly(
    priority: Int = 50,
    action: HookAction
): HookHandle = currentHookBridge.hookMethod(
    this, object : HookCallback(priority) {
        override fun afterHookedMethod(param: HookParam) {
            action(param)
        }
    }, priority
)

fun BaseReflectedMethod.hookDirectly(
    hook: HookCallback
): HookHandle = self.hookDirectly(hook)

fun Executable.hookDirectly(
    hook: HookCallback
): HookHandle = currentHookBridge.hookMethod(this, hook, hook.priority)

fun HookParam.captureOriginalMethod(): OriginalMethodInvoker {
    val method = member as? Method
        ?: throw IllegalStateException("invokeOriginalMethod is only supported for methods: $member")
    return OriginalMethodInvoker(currentHookBridge, method, thisObject, args.copyOf())
}

fun HookParam.invokeOriginalMethod(thisObject: Any? = null, args: Array<Any?>? = null): Any? {
    val method = member as? Method
        ?: throw IllegalStateException("invokeOriginalMethod is only supported for methods: $member")
    return currentHookBridge.invokeOriginalMethod(
        method,
        thisObject ?: this.thisObject,
        args ?: this.args
    )
}
