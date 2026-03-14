package moe.ouom.wekit.loader

import moe.ouom.wekit.loader.hookapi.ILoaderService
import moe.ouom.wekit.loader.startup.UnifiedEntryPoint.entry

object ModuleLoader {

    @JvmStatic
    val initErrors = ArrayList<Throwable?>(1)
    private var isInitialized = false

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun initialize(
        hostClassLoader: ClassLoader,
        loaderService: ILoaderService,
        modulePath: String
    ) {
        if (isInitialized) {
            return
        }
        isInitialized = true
        entry(modulePath, loaderService, hostClassLoader)
    }
}
