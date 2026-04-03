package dev.ujhhgtg.wekit.loader.startup

import android.app.Instrumentation
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.ClassLoaderProvider
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly

object UnifiedEntryPoint {

    private val TAG = nameOf(UnifiedEntryPoint)

    fun entry(
        loaderService: ILoaderService,
        hostClassLoader: ClassLoader,
        modulePath: String
    ) {
        ClassLoaderProvider.classLoader = hostClassLoader
        val self = UnifiedEntryPoint::class.java.classLoader!!
        val selfParent = self.parent
        HybridClassLoader.moduleParentClassLoader = selfParent
        HybridClassLoader.hostClassLoader = hostClassLoader
        self.asResolver()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)
        WeLogger.d(TAG, "injected hybrid class loader")

        com.tencent.mm.app.Application::class.asResolver()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                // Hook Instrumentation.callApplicationOnCreate 以确保在 Tinker 热更新完成后再进行延迟初始化
                // 这可以解决某些模块在热更新环境下找不到入口的问题
                Instrumentation::class.asResolver()
                    .firstMethod {
                        name = "callApplicationOnCreate"
                    }
                    .hookAfterDirectly { _ ->
                        runCatching {
                            StartupAgent.startup(
                                loaderService,
                                modulePath
                            )
                        }.onFailure { WeLogger.e(TAG, "StartupAgent failed", it) }
                    }
            }
    }
}
