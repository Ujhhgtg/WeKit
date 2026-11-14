package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflected.ReflectedMethod
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import java.lang.reflect.Member
import java.lang.reflect.Proxy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HookDslTest {
    private val registrations = mutableListOf<Registration>()
    private var previousBridge: IHookBridge? = null
    private val target = Target::class.java.getDeclaredMethod("value", Int::class.javaPrimitiveType)

    @BeforeEach
    fun installBridge() {
        previousBridge =
            try {
                StartupInfo.hookBridge
            } catch (_: UninitializedPropertyAccessException) {
                null
            }
        StartupInfo.hookBridge =
            Proxy.newProxyInstance(
                IHookBridge::class.java.classLoader,
                arrayOf(IHookBridge::class.java),
            ) { _, method, args ->
                check(method.name == "hookMethod") { "Unexpected bridge call: ${method.name}" }
                Registration(
                        args[0] as Member,
                        args[1] as IHookBridge.IMemberHookCallback,
                        args[2] as Int,
                    )
                    .also { registrations += it }
            } as IHookBridge
    }

    @AfterEach
    fun restoreBridge() {
        StartupInfo::class.java.getDeclaredField("hookBridge").set(null, previousBridge)
    }

    @Test
    fun pairedHookRegistersOnceAndPreservesOuterStateDuringReentry() {
        val completed = mutableListOf<Int>()
        val handle =
            target.hookDirectly(priority = 75) {
                before { extra = args[0] }
                after {
                    completed += extra as Int
                    result = (result as Int) + (extra as Int)
                }
            }
        assertEquals(1, registrations.size)
        assertSame(handle, registrations.single())
        assertEquals(75, registrations.single().priority)

        val callback = registrations.single().callback
        val outer = Parameter(target, arrayOf(10))
        val inner = Parameter(target, arrayOf(20))
        callback.beforeHookedMember(outer)
        callback.beforeHookedMember(inner)
        inner.result = 1
        callback.afterHookedMember(inner)
        outer.result = 2
        callback.afterHookedMember(outer)

        assertEquals(listOf(20, 10), completed)
        assertEquals(21, inner.result)
        assertEquals(12, outer.result)
    }

    @Test
    fun featureHookOwnsReflectedMethodAndConstructorRegistrations() {
        val feature =
            object : BaseFeature() {
                override val technicalId = "hook-dsl-test"
                override val nameRes = 0
                override val categoryIds = emptyList<String>()

                override fun onEnable() {
                    ReflectedMethod<Any>(target).hook(priority = 80) {
                        before { extra = "method" }
                        after { result = extra }
                    }
                    ReflectedConstructor(Target::class.java.getDeclaredConstructor()).hook {
                        before { extra = "constructor" }
                        after { result = extra }
                    }
                }
            }
        feature.onEnable()
        assertEquals(2, registrations.size)
        assertEquals(registrations, feature.unhooks)
        registrations.forEach {
            val param = Parameter(it.member, emptyArray())
            it.callback.beforeHookedMember(param)
            it.callback.afterHookedMember(param)
            assertEquals(if (it.member == target) "method" else "constructor", param.result)
        }
        feature.unhookAll()
        assertTrue(feature.unhooks.isEmpty())
        assertTrue(registrations.none { it.isHookActive })
    }

    @Test
    fun existingSinglePhaseHelpersKeepTheirOwnRegistrations() {
        val calls = mutableListOf<String>()
        target.hookBeforeDirectly { calls += "before" }
        ReflectedMethod<Any>(target).hookAfterDirectly { calls += "after" }
        ReflectedConstructor(Target::class.java.getDeclaredConstructor()).hookDirectly {
            before { calls += "constructor-before" }
            after { calls += "constructor-after" }
        }

        assertEquals(3, registrations.size)
        registrations.forEach { registration ->
            assertEquals(50, registration.priority)
            val param = Parameter(registration.member, emptyArray())
            registration.callback.beforeHookedMember(param)
            registration.callback.afterHookedMember(param)
        }
        assertEquals(listOf("before", "after", "constructor-before", "constructor-after"), calls)
    }

    @Test
    fun directHookPropagatesCallbackFailureAndExposesOriginalFailureToAfter() {
        val failure = IllegalStateException("original failure")
        target.hookDirectly {
            after {
                assertSame(failure, throwable)
                throw UnsupportedOperationException("callback failure")
            }
        }
        val param = Parameter(target, emptyArray()).apply { throwable = failure }
        assertThrows(UnsupportedOperationException::class.java) {
            registrations.single().callback.afterHookedMember(param)
        }
    }

    class Target {
        fun value(input: Int) = input
    }

    private class Registration(
        override val member: Member,
        override val callback: IHookBridge.IMemberHookCallback,
        val priority: Int,
    ) : IHookBridge.MemberUnhookHandle {
        override var isHookActive = true
            private set

        override fun unhook() {
            isHookActive = false
        }
    }

    private class Parameter(
        override val member: Member,
        override val args: Array<Any?>,
    ) : IHookBridge.IMemberHookParam {
        override val thisObject: Any? = null
        override var result: Any? = null
        override var throwable: Throwable? = null
        override var extra: Any? = null
    }
}
