package dev.ujhhgtg.wekit.loader.startup

import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.api.InjectionMode
import dev.ujhhgtg.wekit.loader.api.PayloadEnvironment
import dev.ujhhgtg.wekit.loader.api.TelegramRootAccess

object StartupInfo {
    lateinit var payloadEnvironment: PayloadEnvironment
    val injectionMode: InjectionMode
        get() = payloadEnvironment.injectionMode

    val telegramRootAccess: TelegramRootAccess?
        get() = payloadEnvironment.telegramRootAccess

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    lateinit var hookBridge: IHookBridge
}
