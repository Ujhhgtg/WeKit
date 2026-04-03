package dev.ujhhgtg.wekit.loader.startup

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.hooks.core.HookItemsLoader
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly

object WeLauncher {

    fun init(cl: ClassLoader, context: Context) {
        val processType = TargetProcesses.getCurrentProcessType()
        val currentProcessName = TargetProcesses.getCurrentProcessName()
        WeLogger.d(TAG, "loading in processName=$currentProcessName, type=$processType")

        ParcelableFixer.init(cl, WeLauncher::class.java.classLoader!!)

        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        DexCacheManager.init(pi.versionName!!)

        if (processType == TargetProcesses.PROC_MAIN) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.initForStubActivity(appContext)

            initMainProcessHooks()
        }

        runCatching {
            HookItemsLoader.loadHookItems(processType)
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private fun initMainProcessHooks() {
        LauncherUI::class.asResolver().apply {
            firstMethod { name = "onResume" }.hookAfterDirectly { param ->
                val activity = param.thisObject as Activity
                ModuleRes.init(activity, PackageNames.THIS)
            }

            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfterDirectly { param ->
                val activity = param.thisObject as Activity
                val sharedPreferences =
                    activity.getSharedPreferences("${PackageNames.WECHAT}_preferences", 0)
                RuntimeConfig.setMmPrefs(sharedPreferences)
            }
        }
    }

    private val TAG = nameOf(WeLauncher)
}
