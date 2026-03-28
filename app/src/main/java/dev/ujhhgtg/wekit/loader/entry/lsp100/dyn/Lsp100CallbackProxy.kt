package dev.ujhhgtg.wekit.loader.entry.lsp100.dyn

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookWrapper
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookWrapper.InvocationParamWrapper
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker

@Keep
class Lsp100CallbackProxy private constructor() {

    @XposedHooker
    object P0000000050 : Hooker {
        const val TAG: Int = 50

        @JvmStatic
        @BeforeInvocation
        fun before(callback: BeforeHookCallback): InvocationParamWrapper? {
            return Lsp100HookWrapper.Lsp100HookAgent.handleBeforeHookedMethod(callback, TAG)
        }

        @JvmStatic
        @AfterInvocation
        fun after(callback: AfterHookCallback, param: InvocationParamWrapper?) {
            Lsp100HookWrapper.Lsp100HookAgent.handleAfterHookedMethod(callback, param, TAG)
        }
    }

    @XposedHooker
    object P0000000051 : Hooker {
        const val TAG: Int = 51

        @JvmStatic
        @BeforeInvocation
        fun before(callback: BeforeHookCallback): InvocationParamWrapper? {
            return Lsp100HookWrapper.Lsp100HookAgent.handleBeforeHookedMethod(callback, TAG)
        }

        @JvmStatic
        @AfterInvocation
        fun after(callback: AfterHookCallback, param: InvocationParamWrapper?) {
            Lsp100HookWrapper.Lsp100HookAgent.handleAfterHookedMethod(callback, param, TAG)
        }
    }
}
