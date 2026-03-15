package moe.ouom.wekit.loader.modern.dyn

import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import moe.ouom.wekit.loader.modern.Lsp100HookWrapper

@Keep
class Lsp100CallbackProxy private constructor() {

    @Keep
    class P0000000050 : XposedInterface.Hooker {
        companion object {
            const val TAG = 50

            @Keep
            @JvmStatic
            fun before(callback: XposedInterface.BeforeHookCallback): Lsp100HookWrapper.InvocationParamWrapper? =
                Lsp100HookWrapper.Lsp100HookAgent.handleBeforeHookedMethod(callback, TAG)

            @Keep
            @JvmStatic
            fun after(callback: XposedInterface.AfterHookCallback, param: Lsp100HookWrapper.InvocationParamWrapper?) =
                Lsp100HookWrapper.Lsp100HookAgent.handleAfterHookedMethod(callback, param)
        }
    }

    @Keep
    class P0000000051 : XposedInterface.Hooker {
        companion object {
            const val TAG = 51

            @Keep
            @JvmStatic
            fun before(callback: XposedInterface.BeforeHookCallback): Lsp100HookWrapper.InvocationParamWrapper? =
                Lsp100HookWrapper.Lsp100HookAgent.handleBeforeHookedMethod(callback, TAG)

            @Keep
            @JvmStatic
            fun after(callback: XposedInterface.AfterHookCallback, param: Lsp100HookWrapper.InvocationParamWrapper?) =
                Lsp100HookWrapper.Lsp100HookAgent.handleAfterHookedMethod(callback, param)
        }
    }
}
