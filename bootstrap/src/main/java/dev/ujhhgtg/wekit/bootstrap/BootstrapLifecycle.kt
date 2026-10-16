package dev.ujhhgtg.wekit.bootstrap

import android.app.Application
import android.app.Instrumentation
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.api.InjectionMode
import dev.ujhhgtg.wekit.loader.api.TelegramRootAccess
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Connects the real host Application lifecycle to remote-payload initialization. */
object BootstrapLifecycle {
    private var installed = false

    @Synchronized
    fun install(
        hostLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge,
        injectionMode: InjectionMode,
        telegramRootAccess: TelegramRootAccess?,
    ) {
        if (installed) return
        HiddenApiBypass.setHiddenApiExemptions("L")
        val member = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        hookBridge.hookMethod(member, object : IHookBridge.IMemberHookCallback {
            override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = Unit
            override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
                if (param.throwable != null) return
                val application = param.args[0] as Application
                if (application.packageName.startsWith("com.tencent.mm")) {
                    BootstrapCoordinator.start(application, application.baseContext.classLoader ?: hostLoader,
                        loaderService, hookBridge, injectionMode, telegramRootAccess)
                }
            }
        }, 10000)
        installed = true
    }
}
