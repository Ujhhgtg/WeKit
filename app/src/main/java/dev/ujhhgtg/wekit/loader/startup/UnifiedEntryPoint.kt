package dev.ujhhgtg.wekit.loader.startup

import android.app.Instrumentation
import com.highcapable.kavaref.extension.ClassLoaderProvider
import com.tencent.mm.app.Application
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve

object UnifiedEntryPoint {

    private val TAG = This.Class.simpleName

    fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        hostClassLoader: ClassLoader,
        modulePath: String
    ) {
        ClassLoaderProvider.classLoader = hostClassLoader
        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        HybridClassLoader.moduleParentClassLoader = selfParent
        HybridClassLoader.hostClassLoader = hostClassLoader
        self.asResolver()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        Application::class.resolve()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                // Hook Instrumentation.callApplicationOnCreate 以确保在 Tinker 热更新完成后再进行延迟初始化
                // 这可以解决某些模块在热更新环境下找不到入口的问题
                Instrumentation::class.resolve()
                    .firstMethod {
                        name = "callApplicationOnCreate"
                    }
                    .hookAfterDirectly {
                        runCatching {
                            StartupAgent.startup(
                                loaderService,
                                hookBridge,
                                modulePath
                            )
                        }.onFailure { WeLogger.e(TAG, "StartupAgent failed", it) }
                    }
            }
    }
}
