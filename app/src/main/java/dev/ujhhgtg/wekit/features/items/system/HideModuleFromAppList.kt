package dev.ujhhgtg.wekit.features.items.system

import android.app.ApplicationPackageManager
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object HideModuleFromAppList : SwitchFeature() {

    override val technicalId = "隐藏模块应用"
    override val nameRes = R.string.feature_hide_module_from_app_list_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_hide_module_from_app_list_description

    private const val TAG = "HideModuleFromAppList"

    override fun onEnable() {
        ApplicationPackageManager::class.reflekt().apply {
            firstMethod {
                name = "queryIntentActivities"
            }.hookAfter {
                @Suppress("UNCHECKED_CAST")
                val infos = result as MutableList<ResolveInfo>
                infos.removeAll { info ->
                    (info.activityInfo.packageName == PackageNames.MODULE).also {
                        if (it) WeLogger.i(TAG, "removed module from PackageManager::queryIntentActivities")
                    }
                }
            }

            methods {
                name = "getPackageInfo"
                parameters { it[0] == String::class.java }
            }.forEach {
                it.hookBefore {
                    val pkg = args[0] as String
                    if (pkg == PackageNames.MODULE) {
                        throwable = PackageManager.NameNotFoundException(pkg)
                        WeLogger.i(TAG, "thrown NameNotFoundException from PackageManager::getPackageInfo")
                    }
                }
            }

            methods {
                name = "getApplicationInfo"
                parameters { it[0] == String::class.java }
            }.forEach {
                it.hookBefore {
                    val pkg = args[0] as String
                    if (pkg == PackageNames.MODULE) {
                        throwable = PackageManager.NameNotFoundException(pkg)
                        WeLogger.i(TAG, "thrown NameNotFoundException from PackageManager::getApplicationInfo")
                    }
                }
            }
        }
    }
}
