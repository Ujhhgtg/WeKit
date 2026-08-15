package dev.ujhhgtg.wekit.loader.startup

import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
