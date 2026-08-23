package dev.ujhhgtg.wekit.features.items.system

import android.app.ActivityThread
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.WeLogger

// https://github.com/Ujhhgtg/PandorasBox
object PredictiveBackGestures : ApiFeature() {

    override val technicalId = "预见性返回动画"
    override val nameRes = R.string.feature_predictive_back_gestures_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_predictive_back_gestures_description

    private const val PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 2
    private const val PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3
    private const val PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3

    private const val TAG = "PredictiveBackGestures"

    override fun onEnable() {
        if (!ThemeSettings.appliedPredictiveBackEnabled) {
            WeLogger.i(TAG, "predictive back animation is off")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WeLogger.w(TAG, "sdk < 33, not enabling predictive back gestures")
            return
        }

        ApplicationInfo::class.reflekt()
            .firstConstructor {
                parameters(ApplicationInfo::class.java)
            }.hookAfter {
                val info = args[0] as ApplicationInfo
                val field =
                    info.reflekt().firstField { name = "privateFlagsExt" }
                var flags = field.get() as Int
                flags = flags or PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK
                field.set(flags)
            }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                if (!isModuleActivity(info)) return@hookAfter
                applyFlag(info)
            }

        ActivityThread::class.reflekt()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0]!!
                val infoField =
                    record.reflekt().firstField { name = "activityInfo" }
                val info = infoField.get() as ActivityInfo
                val intent = record.reflekt().firstField { name = "intent" }.get() as? Intent
                if (!isModuleActivity(info, intent)) return@hookBefore
                applyFlag(info)
            }
    }

    /**
     * [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] recovers the target Intent before ActivityThread launches a module Activity,
     * but must keep the host stub's ActivityInfo. Inspect the recovered component as well, so the
     * host ActivityInfo receives the predictive-back flags for the Activity it will instantiate.
     */
    private fun isModuleActivity(info: ActivityInfo, intent: Intent? = null): Boolean =
        info.name?.startsWith(PackageNames.MODULE) == true ||
            intent?.component?.className?.startsWith(PackageNames.MODULE) == true

    private fun applyFlag(info: ActivityInfo) {
        val field = info.reflekt().firstField { name = "privateFlags" }
        var flags = field.get() as Int
        flags = flags or PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
        flags = flags and PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK.inv()
        field.set(flags)
    }

}
