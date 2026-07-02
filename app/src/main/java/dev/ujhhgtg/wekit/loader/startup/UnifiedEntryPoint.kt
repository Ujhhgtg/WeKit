package dev.ujhhgtg.wekit.loader.startup

import android.app.Application
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

object UnifiedEntryPoint {

    private val TAG = This.Class.simpleName

    fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                val currentClassLoader = (thisObject as Application).classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod {
                        name = "callApplicationOnCreate"
                    }
                    .hookAfterDirectly {
                        runCatching {
                            val application = args[0] as Application
                            val realClassLoader = application.baseContext.classLoader

                            StartupAgent.startup(
                                loaderService,
                                hookBridge,
                                modulePath,
                                application,
                                realClassLoader
                            )
                        }.onFailure { WeLogger.e(TAG, "StartupAgent failed", it) }
                    }
            }
    }
}
