package dev.ujhhgtg.wekit.hooks.core

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.dsl.DexConstructorDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexDelegateBase
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.utils.HookAction
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import java.lang.reflect.Executable
import kotlin.reflect.KClass

abstract class BaseHookItem {

    var path: String = ""

    var description: String = ""

    open fun startup() {
        error("You shouldn't inherit BaseHookItem")
    }

    var hasEnabled: Boolean = false
        private set

    fun enable() {
        if (hasEnabled) return
        runCatching {
            hasEnabled = true
            onEnable()
        }.onFailure { e -> WeLogger.e(TAG, "failed to enable item", e) }
    }

    fun disable() {
        if (!hasEnabled) return
        runCatching {
            hasEnabled = false
            onDisable()
        }.onFailure { e -> WeLogger.e(TAG, "failed to disable item", e) }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    private val _dexDelegates = mutableListOf<DexDelegateBase>()
    val dexDelegates: List<DexDelegateBase> get() = _dexDelegates
    internal fun registerDexDelegate(d: DexDelegateBase) {
        _dexDelegates += d
    }

    // --- hookBefore ---

    inline fun Executable.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ): XC_MethodHook.Unhook = XposedBridge.hookMethod(
        this,
        object :
            XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                executeHookAction(param, action)
            }
        }
    )

    @JvmName("hookBefore2")
    inline fun MethodResolver<*>.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    inline fun ConstructorResolver<*>.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookBefore(priority, action)

    inline fun Class<*>.hookBeforeOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookBefore(50, action)

    inline fun Class<*>.hookAfterOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookAfter(50, action)

    inline fun KClass<*>.hookBeforeOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookBefore(50, action)

    inline fun KClass<*>.hookAfterOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookAfter(50, action)

    // --- end hookBefore ---

    // --- hookAfter ---

    inline fun Executable.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ): XC_MethodHook.Unhook = XposedBridge.hookMethod(
        this,
        object :
            XC_MethodHook(priority) {
            override fun afterHookedMethod(param: MethodHookParam) {
                executeHookAction(param, action)
            }
        }
    )

    @JvmName("hookAfter2")
    inline fun MethodResolver<*>.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    inline fun ConstructorResolver<*>.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    inline fun DexMethodDelegate.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = method.hookBefore(priority, action)

    inline fun DexMethodDelegate.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = method.hookAfter(priority, action)

    inline fun DexConstructorDelegate.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = constructor.hookBefore(priority, action)

    inline fun DexConstructorDelegate.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = constructor.hookAfter(priority, action)

    // --- end dex delegate ---

    inline fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        if (this is SwitchHookItem && !this.isEnabled) return
        if (!hasEnabled) return
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $path", e) }
    }

    companion object {
        private val TAG = nameOf(BaseHookItem::class)
    }
}
