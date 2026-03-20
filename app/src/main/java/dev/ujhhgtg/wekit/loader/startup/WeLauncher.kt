package dev.ujhhgtg.wekit.loader.startup

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.hooks.utils.HookItemsLoader
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.logging.WeLogger

object WeLauncher {

    fun init(cl: ClassLoader, context: Context) {
        val processType = TargetProcesses.getCurrentProcessType()
        val currentProcessName = TargetProcesses.getCurrentProcessName()
        WeLogger.d(TAG, "launching in processName=$currentProcessName, type=$processType")

        ParcelableFixer.init(cl, WeLauncher::class.java.classLoader!!)

        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        DexCacheManager.init(requireNotNull(pi.versionName))

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
        val launcherUiClass = LAUNCHER_UI_CLASS_NAME.toClass()

        XposedHelpers.findAndHookMethod(launcherUiClass, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                ModuleRes.init(activity, PackageNames.THIS)
            }
        })

        XposedHelpers.findAndHookMethod(
            launcherUiClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    RuntimeConfig.setLauncherUiActivity(activity)
                    val sharedPreferences =
                        activity.getSharedPreferences("com.tencent.mm_preferences", 0)
                    RuntimeConfig.setMmPrefs(sharedPreferences)
                }
            })
    }

    private const val LAUNCHER_UI_CLASS_NAME = "com.tencent.mm.ui.LauncherUI"
    private val TAG = nameof(WeLauncher)
}
