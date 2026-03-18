package moe.ouom.wekit.loader.startup

import android.app.Application
import moe.ouom.wekit.loader.abc.ILoaderService

object StartupInfo {

    lateinit var hostApplication: Application
    lateinit var loaderService: ILoaderService

    private var _modulePath: String? = null
    var modulePath: String
        get() = _modulePath ?: error("Module path is null")
        set(value) { _modulePath = value }
}
