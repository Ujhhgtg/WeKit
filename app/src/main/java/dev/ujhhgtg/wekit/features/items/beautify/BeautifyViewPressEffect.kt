package dev.ujhhgtg.wekit.features.items.beautify

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "美化组件按下效果",
    nameRes = "feature_beautify_view_press_effect_name",
    categoryIds = [FeatureCategoryIds.BEAUTIFY],
    descriptionRes = "feature_beautify_view_press_effect_description",
)
object BeautifyViewPressEffect : SwitchFeature() {

    override fun onEnable() {
        View::class.reflekt()
            .firstMethod {
                name = "setBackgroundDrawable"
                parameters(Drawable::class)
            }
            .hookBefore {
                val view = thisObject as View
                val original = args[0] as? Drawable?
                if (view.javaClass.name.startsWith("android.")) return@hookBefore
                if (original != null && original is RippleDrawable) return@hookBefore

                if (view.isClickable) {
                    val rippleColor = ColorStateList.valueOf(0x1F000000)
                    val newRipple = RippleDrawable(rippleColor, original, null)
                    args[0] = newRipple
                }
            }
    }
}
