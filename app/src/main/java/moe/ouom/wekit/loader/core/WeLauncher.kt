package moe.ouom.wekit.loader.core

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.RuntimeConfig
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.hooks.utils.HookItemsLoader
import moe.ouom.wekit.utils.ModuleRes
import moe.ouom.wekit.utils.TargetProcessUtils
import moe.ouom.wekit.utils.logging.WeLogger

object WeLauncher {

    fun init(cl: ClassLoader, context: Context) {
        val processType = TargetProcessUtils.getCurrentProcessType()
        val currentProcessName = TargetProcessUtils.getCurrentProcessName()
        WeLogger.i(TAG, "launching in processName=$currentProcessName, type=$processType")

        ParcelableFixer.init(cl, WeLauncher::class.java.classLoader!!)
        WeLogger.i(TAG, "ParcelableFixer installed")

        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        DexCacheManager.init(requireNotNull(pInfo.versionName))

        if (processType == TargetProcessUtils.PROC_MAIN) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.initForStubActivity(appContext)
            WeLogger.i(TAG, "ActivityProxy installed")

            initMainProcessHooks()
        } else {
            WeLogger.i(TAG, "skipping UI hooks for non-main process: $currentProcessName")
        }

        runCatching {
            HookItemsLoader.loadHookItems(processType)
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private fun initMainProcessHooks() {
        WeLogger.i(TAG, "Initializing Main Process Hooks...")

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
