package moe.ouom.wekit.loader.startup

import android.app.Application
import android.app.Instrumentation
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.ClassLoaderProvider
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.loader.abc.ILoaderService
import moe.ouom.wekit.utils.hookAfterDirectly
import moe.ouom.wekit.utils.logging.WeLogger

object UnifiedEntryPoint {

    private val TAG = nameof(UnifiedEntryPoint)

    fun entry(
        loaderService: ILoaderService,
        hostClassLoader: ClassLoader,
        modulePath: String
    ) {
        ClassLoaderProvider.classLoader = hostClassLoader

        "com.tencent.mm.app.Application".toClass().asResolver()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                // Hook Instrumentation.callApplicationOnCreate 以确保在 Tinker 热更新完成后再进行延迟初始化
                // 这可以解决某些模块在热更新环境下找不到入口的问题
                Instrumentation::class.asResolver()
                    .firstMethod {
                        name = "callApplicationOnCreate"
                    }
                    .hookAfterDirectly { param ->
                        val hostApp = param.args[0] as Application
                        StartupInfo.hostApplication = hostApp

                        try {
                            StartupAgent.startup(
                                modulePath,
                                loaderService
                            )
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "StartupAgent failed", e)
                        }
                    }
            }
    }
}
