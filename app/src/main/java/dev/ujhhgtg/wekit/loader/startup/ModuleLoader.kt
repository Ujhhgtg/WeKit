package dev.ujhhgtg.wekit.loader.startup

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dex.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.FeaturesLoader
import dev.ujhhgtg.wekit.i18n.LocaleController
import dev.ujhhgtg.wekit.loader.api.PayloadEnvironment
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

/** Runs business startup against the already-created real host Application. */
object ModuleLoader {
    private const val TAG = "ModuleLoader"
    private var started = false

    fun startPayload(environment: PayloadEnvironment) {
        // PayloadEntryImpl is created reflectively by the bootstrap. Keep an explicit D8 edge so
        // debug payload builds do not discard the entry class before the reflective load.
        check(PayloadEntryImpl::class.java.name.isNotEmpty()) {
            "payload entry class is unavailable"
        }
        check(!started) { "business payload has already started in this process" }
        // Publish the actual hook bridge before any business hook helper is used.
        StartupInfo.payloadEnvironment = environment
        StartupInfo.hookBridge = environment.hookBridge
        StartupInfo.loaderService = environment.loaderService
        StartupInfo.modulePath = "/proc/self/fd/${environment.payloadApk.fd}"
        ReflectionClassLoader.value = environment.hostClassLoader
        val self = ClassLoaders.MODULE
        HybridClassLoader.moduleParentClassLoader = self.parent!!
        HybridClassLoader.moduleClassLoader = self
        HybridClassLoader.hostClassLoader = environment.hostClassLoader
        self
            .reflekt()
            .firstField {
                name = "parent"
                superclass()
            }
            .set(HybridClassLoader)
        check(R.string.res_inject_success ushr 24 == 0x69) {
            "payload resource package ID must be 0x69"
        }
        NativeLoader.configurePayload(
            environment.payloadApk,
            environment.payloadSha256,
            environment.nativeDirectory,
            environment.bootstrapApk,
        )
        HostInfo.init(environment.hostApplication)
        NativeLoader.init()
        launch(environment.hostApplication)
        started = true
    }

    private fun launch(context: Context) {
        WeLogger.d(
            TAG,
            "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}",
        )

        ParcelableFixer.init()

        DexCacheManager.init()

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        LocaleController.initialize(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences(
                    "${PackageNames.WECHAT}_preferences",
                    Context.MODE_PRIVATE,
                )
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }
            .onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }
}
