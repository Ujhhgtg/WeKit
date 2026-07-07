package dev.ujhhgtg.wekit.loader.entry.common

import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.startup.UnifiedEntryPoint
import dev.ujhhgtg.wekit.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private var isInitialized = false

//    private lateinit var savedHostClassLoader: ClassLoader
//    private lateinit var savedModulePath: String
//
//    fun saveInitParams(
//        hostClassLoader: ClassLoader,
//        modulePath: String
//    ) {
//        savedHostClassLoader = hostClassLoader
//        savedModulePath = modulePath
//    }

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ) {
        if (isInitialized) return
        isInitialized = true

//        ClassLoaderRegistry.hookConstructors()

        WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
        runCatching {
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
    }

//    fun hotReload(loaderService: ILoaderService, hookBridge: IHookBridge?) {
//        WeLogger.i(TAG, "hot-reloading in entry point ${loaderService.entryPointName}")
//        runCatching {
//            UnifiedEntryPoint.entry(loaderService, hookBridge, savedHostClassLoader, savedModulePath)
//        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
//    }
}
