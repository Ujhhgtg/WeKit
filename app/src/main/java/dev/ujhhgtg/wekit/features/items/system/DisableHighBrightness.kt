package dev.ujhhgtg.wekit.features.items.system

import android.view.WindowManager
import com.android.internal.policy.PhoneWindow
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "禁止屏幕高亮度",
    nameRes = "feature_disable_high_brightness_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_disable_high_brightness_description",
)
object DisableHighBrightness : SwitchFeature() {

    override fun onEnable() {
        PhoneWindow::class.reflekt()
            .firstMethod {
                name = "setAttributes"
                parameters(WindowManager.LayoutParams::class)
            }
            .hookBefore {
                val lp = args[0] as WindowManager.LayoutParams
                if (lp.screenBrightness >= 0.5f) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
    }
}
