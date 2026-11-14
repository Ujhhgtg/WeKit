package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.wekit.dex.dsl.DexConstructorDelegate
import dev.ujhhgtg.wekit.dex.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.environment.MethodAccessFlags
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import java.lang.reflect.Executable
import java.lang.reflect.Method

typealias HookParam = IHookBridge.IMemberHookParam

typealias HookHandle = IHookBridge.MemberUnhookHandle

typealias HookAction = HookParam.() -> Unit

abstract class HookCallback(val priority: Int = 50) : IHookBridge.IMemberHookCallback {
    override fun beforeHookedMember(param: HookParam) {}

    override fun afterHookedMember(param: HookParam) {}
}

@DslMarker annotation class HookDsl

/** Configures one registration whose before/after phases share [HookParam.extra] per invocation. */
@HookDsl
class HookBuilder(
    priority: Int = 50,
    private val executeAction: (HookParam, HookAction) -> Unit = { param, action -> action(param) },
) : HookCallback(priority) {
    private var beforeAction: HookAction? = null
    private var afterAction: HookAction? = null

    fun before(action: HookAction) {
        beforeAction = action
    }

    fun after(action: HookAction) {
        afterAction = action
    }

    override fun beforeHookedMember(param: HookParam) {
        beforeAction?.let { executeAction(param, it) }
    }

    override fun afterHookedMember(param: HookParam) {
        afterAction?.let { executeAction(param, it) }
    }
}

class OriginalMethodInvoker(
    private val hookBridge: IHookBridge,
    private val method: Method,
    private val thisObject: Any?,
    private val originalArgs: Array<Any?>,
) {
    operator fun invoke(args: Array<Any?>? = null): Any? =
        hookBridge.invokeOriginalMethod(method, thisObject, args ?: originalArgs)
}

inline val currentHookBridge: IHookBridge
    get() = StartupInfo.hookBridge

// most extension methods are inside BaseFeature for enabled state checking

fun BaseReflectedMethod.hookBeforeDirectly(priority: Int = 50, action: HookAction) =
    self.hookBeforeDirectly(priority, action)

fun Executable.hookBeforeDirectly(priority: Int = 50, action: HookAction): HookHandle =
    hookDirectly(priority) { before(action) }

fun BaseReflectedMethod.hookAfterDirectly(priority: Int = 50, action: HookAction): HookHandle =
    self.hookAfterDirectly(priority, action)

fun ReflectedConstructor<*>.hookAfterDirectly(priority: Int = 50, action: HookAction): HookHandle =
    self.hookAfterDirectly(priority, action)

fun Executable.hookAfterDirectly(priority: Int = 50, action: HookAction): HookHandle =
    hookDirectly(priority) { after(action) }

/** Registers both phases together; the caller owns the returned unhook handle. */
fun Executable.hookDirectly(priority: Int = 50, block: HookBuilder.() -> Unit): HookHandle =
    hookDirectly(HookBuilder(priority).apply(block))

fun BaseReflectedMethod.hookDirectly(
    priority: Int = 50,
    block: HookBuilder.() -> Unit,
): HookHandle = self.hookDirectly(priority, block)

fun ReflectedConstructor<*>.hookDirectly(
    priority: Int = 50,
    block: HookBuilder.() -> Unit,
): HookHandle = self.hookDirectly(priority, block)

fun DexMethodDelegate.hookDirectly(priority: Int = 50, block: HookBuilder.() -> Unit): HookHandle =
    method.hookDirectly(priority, block)

fun DexConstructorDelegate.hookDirectly(
    priority: Int = 50,
    block: HookBuilder.() -> Unit,
): HookHandle = constructor.hookDirectly(priority, block)

fun BaseReflectedMethod.hookDirectly(hook: HookCallback): HookHandle = self.hookDirectly(hook)

fun Executable.hookDirectly(hook: HookCallback): HookHandle {
    MethodAccessFlags.captureBeforeHook(this)
    return currentHookBridge.hookMethod(this, hook, hook.priority)
}

fun HookParam.captureOriginalMethod(): OriginalMethodInvoker {
    val method =
        member as? Method
            ?: throw IllegalStateException(
                "invokeOriginalMethod is only supported for methods: $member",
            )
    return OriginalMethodInvoker(currentHookBridge, method, thisObject, args.copyOf())
}

fun HookParam.invokeOriginalMethod(thisObject: Any? = null, args: Array<Any?>? = null): Any? {
    val method =
        member as? Method
            ?: throw IllegalStateException(
                "invokeOriginalMethod is only supported for methods: $member",
            )
    return currentHookBridge.invokeOriginalMethod(
        method,
        thisObject ?: this.thisObject,
        args ?: this.args,
    )
}
