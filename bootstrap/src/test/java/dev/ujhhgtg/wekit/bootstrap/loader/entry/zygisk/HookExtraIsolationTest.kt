package dev.ujhhgtg.wekit.bootstrap.loader.entry.zygisk

import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HookExtraIsolationTest {
    @Test
    fun registrationsAndRecursiveInvocationsKeepSeparateExtras() {
        val target = Target()
        val method = Target::class.java.getDeclaredMethod("value", Int::class.javaPrimitiveType)
        val entry = ArtHookBridgeRuntime.HookEntry(method)
        entry.completeInstallation(method)
        val seen = mutableListOf<String>()
        val callback = object : IHookBridge.IMemberHookCallback {
            override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
                assertNull(param.extra)
                param.extra = "${seen.size}:${param.args[0]}"
                seen += "before:${param.extra}"
            }

            override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                seen += "after:${param.extra}"
            }
        }
        entry.addCallback(ArtHookBridgeRuntime.PrioritizedCallback(callback, 80))
        entry.addCallback(ArtHookBridgeRuntime.PrioritizedCallback(callback, 40))
        target.invokeNested = { entry.callback(arrayOf(target, 2)) as Int }

        assertEquals(3, entry.callback(arrayOf(target, 1)))
        assertEquals(
            listOf(
                "before:0:1", "before:1:1",
                "before:2:2", "before:3:2",
                "after:3:2", "after:2:2",
                "after:1:1", "after:0:1",
            ),
            seen,
        )
    }

    class Target {
        lateinit var invokeNested: () -> Int

        fun value(input: Int): Int = if (input == 1) input + invokeNested() else input
    }
}
