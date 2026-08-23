package dev.ujhhgtg.wekit.features.items.system

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "禁用存储空间不足检测",
    nameRes = "feature_disable_low_available_storage_detection_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_disable_low_available_storage_detection_description",
)
object DisableLowAvailableStorageDetection : SwitchFeature(), IResolveDex {

    private val methodSplashActivitySplashFinished by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.splash.SplashActivity"
            usingEqStrings("WxSplash.SplashActivity", "Call splashFinished.")
        }
    }
    private val classStaticValuesHolder by dexClass {
        matcher {
            usingEqStrings("UIPageFragmentActivity", "LuckyMoneyNewPrepareUI", "RemittanceUI")
        }
    }

    override fun onEnable() {
        methodSplashActivitySplashFinished.hookBefore {
            classStaticValuesHolder.clazz.reflekt()
                .firstField { type = Boolean::class }
                .setStatic(false)
        }

        "com.tencent.mm.plugin.clean.ui.fileindexui.StorageDisableAlertUI"
            .toClass().hookAfterOnCreate {
                val activity = thisObject as Activity
                activity.finish()
            }
    }
}
