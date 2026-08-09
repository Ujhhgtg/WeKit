package dev.ujhhgtg.wekit.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.FeaturesLoader
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        WeKitLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
