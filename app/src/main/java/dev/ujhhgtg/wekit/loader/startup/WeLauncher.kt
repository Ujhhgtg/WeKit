package dev.ujhhgtg.wekit.loader.startup

import android.content.Context
import android.content.res.Resources
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.hooks.core.HookItemsLoader
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.invokeOriginal
import dev.ujhhgtg.wekit.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init("${HostInfo.versionName}${HostInfo.versionCode}")

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs

            initMainProcessHooks()
        }

        runCatching {
            HookItemsLoader.loadHookItems()
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private fun initMainProcessHooks() {
        // fix up Jetpack Compose
        Resources::class.java.getDeclaredMethod("getString", int).hookBeforeDirectly {
            result = runCatching { invokeOriginal() }.getOrNull() ?: "null"
        }

        LauncherUI::class.reflekt().apply {
            // FIXME: see BasePrefsScreen line 298
//            firstMethod { name = "onCreate" }.hookBeforeDirectly {
//                Handler(Looper.getMainLooper()).post {
//                    while (true) {
//                        try {
//                            Looper.loop()
//                        } catch (e: Throwable) {
//                            if (e is NullPointerException && e.message?.contains("android.view.InputEventCompatProcessor.processInputEventForCompatibility(android.view.InputEvent)") == true) {
//                                WeLogger.e("FuckYouGoogle", "fuck you google", e)
//                            } else {
//                                throw e
//                            }
//                        }
//                    }
//                }
//            }
        }
    }

    private val TAG = This.Class.simpleName
}
