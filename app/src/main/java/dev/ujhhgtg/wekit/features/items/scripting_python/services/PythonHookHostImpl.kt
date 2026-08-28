package dev.ujhhgtg.wekit.features.items.scripting_python.services

import dev.ujhhgtg.wekit.dexkit.DexMethodDescriptor
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginScope
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonCrashGuard
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLoader
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.python.api.PythonHookCallback
import dev.ujhhgtg.wekit.python.api.PythonHookHost
import dev.ujhhgtg.wekit.python.api.PythonHookToken
import dev.ujhhgtg.wekit.python.api.PythonMemberHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.currentHookBridge
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import java.lang.reflect.Executable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

internal class PythonHookHostImpl(
    private val pluginId: String,
    private val scope: PythonPluginScope,
) : PythonHookHost {
    private val hooks = ConcurrentHashMap<String, IHookBridge.MemberUnhookHandle>()
    override fun before(member: Any, callback: PythonHookCallback, priority: Int): PythonHookToken =
        install(member, callback, priority, HookKind.BEFORE)

    override fun after(member: Any, callback: PythonHookCallback, priority: Int): PythonHookToken =
        install(member, callback, priority, HookKind.AFTER)

    override fun replace(member: Any, callback: PythonHookCallback, priority: Int): PythonHookToken =
        install(member, callback, priority, HookKind.REPLACE)

    override fun invokeOriginal(parameter: Any): Any? {
        val param = parameter as IHookBridge.IMemberHookParam
        return when (val member = param.member) {
            is Method -> currentHookBridge.invokeOriginalMethod(member, param.thisObject, param.args)
            is Constructor<*> -> {
                @Suppress("UNCHECKED_CAST")
                currentHookBridge.invokeOriginalConstructor(
                    member as Constructor<Any?>,
                    param.thisObject!!,
                    param.args,
                )
                param.thisObject
            }
            else -> error("Unsupported hooked member: $member")
        }
    }

    override fun unhook(token: PythonHookToken) {
        val hook = hooks.remove(token.id) ?: error("Unknown Python hook token: ${token.id}")
        hook.unhook()
    }

    private fun install(
        member: Any,
        callback: PythonHookCallback,
        priority: Int,
        kind: HookKind,
    ): PythonHookToken {
        check(!scope.isClosed) { "Python plugin scope is closed: $pluginId" }
        val executable = when (member) {
            is Executable -> member
            is PythonMemberHandle -> resolve(member.descriptor)
            else -> error("Unsupported hook member: $member")
        }
        val hook = currentHookBridge.hookMethod(
            executable,
            object : IHookBridge.IMemberHookCallback {
                override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
                    if (kind == HookKind.AFTER) return
                    if (scope.isClosed) return
                    val oldResult = param.result
                    val oldThrowable = param.throwable
                    var originalInvoked = false
                    val crashToken = PythonCrashGuard.begin(pluginId, "hook-${kind.name.lowercase()}")
                    val elapsed = measureTimeMillis {
                        try {
                            val returned = PythonRuntimeLoader.withLookupClassLoader { callback.invoke(param) }
                            if (kind == HookKind.REPLACE) param.result = returned
                        } catch (error: Throwable) {
                            WeLogger.e("Python/$pluginId", "hook callback failed open", error)
                            param.result = oldResult
                            param.throwable = oldThrowable
                            if (kind == HookKind.REPLACE) {
                                param.result = invokeOriginal(param)
                                originalInvoked = true
                            }
                        } finally {
                            PythonCrashGuard.finish(crashToken)
                        }
                    }
                    if (elapsed > PythonRuntimeLoader.syncHookBudgetMs()) {
                        WeLogger.w("Python/$pluginId", "hook callback exceeded budget: ${elapsed}ms")
                        param.result = oldResult
                        param.throwable = oldThrowable
                        if (kind == HookKind.REPLACE && !originalInvoked) param.result = invokeOriginal(param)
                    }
                }

                override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                    if (kind != HookKind.AFTER) return
                    if (scope.isClosed) return
                    val oldResult = param.result
                    val oldThrowable = param.throwable
                    val crashToken = PythonCrashGuard.begin(pluginId, "hook-after")
                    val elapsed = measureTimeMillis {
                        try {
                            PythonRuntimeLoader.withLookupClassLoader { callback.invoke(param) }
                        } catch (error: Throwable) {
                            WeLogger.e("Python/$pluginId", "after hook callback failed open", error)
                            param.result = oldResult
                            param.throwable = oldThrowable
                        } finally {
                            PythonCrashGuard.finish(crashToken)
                        }
                    }
                    if (elapsed > PythonRuntimeLoader.syncHookBudgetMs()) {
                        WeLogger.w("Python/$pluginId", "after hook callback exceeded budget: ${elapsed}ms")
                        param.result = oldResult
                        param.throwable = oldThrowable
                    }
                }
            },
            priority,
        )
        val token = PythonHookToken(UUID.randomUUID().toString())
        hooks[token.id] = hook
        try {
            scope.track(callback) { hooks.remove(token.id)?.unhook() }
        } catch (error: Throwable) {
            hooks.remove(token.id)?.unhook()
            throw error
        }
        return token
    }

    private fun resolve(descriptor: String): Executable {
        val parsed = DexMethodDescriptor(descriptor)
        return if (parsed.name == "<init>") {
            parsed.getConstructorInstance(ClassLoaders.HYBRID)
        } else {
            parsed.getMethodInstance(ClassLoaders.HYBRID)
        }
    }

    private enum class HookKind { BEFORE, AFTER, REPLACE }
}
