package dev.ujhhgtg.wekit.features.items.system

import android.os.PowerManager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "省电模式",
    nameRes = "feature_power_saver_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_power_saver_description",
)
object PowerSaver : SwitchFeature() {

    override fun onEnable() {
        PowerManager.WakeLock::class.reflekt().apply {
            methods {
                name = "acquire"
            }.forEach {
                it.hookBefore { result = null }
            }

            firstMethod {
                name = "release"
                parameterCount = 1
            }.hookBefore { result = null }
        }
    }
}
