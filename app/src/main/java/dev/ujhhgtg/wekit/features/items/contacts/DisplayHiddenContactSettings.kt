package dev.ujhhgtg.wekit.features.items.contacts

import android.widget.BaseAdapter
import com.tencent.mm.plugin.profile.ui.ProfileSettingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "显示隐藏朋友设置项",
    nameRes = "feature_display_hidden_contact_settings_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_display_hidden_contact_settings_description",
)
object DisplayHiddenContactSettings : SwitchFeature() {

    override fun onEnable() {
        ProfileSettingUI::class.reflekt()
            .firstMethod {
                name = "initView"
            }.hookAfter {
                val prefScreen = thisObject!!.reflekt()
                    .firstMethod {
                        name = "getPreferenceScreen"
                        superclass()
                    }.invoke()!!
                val hiddenSet = prefScreen.reflekt()
                    .firstField {
                        type = HashSet::class
                    }.get()!! as HashSet<*>
                hiddenSet.clear()
                (prefScreen as BaseAdapter).notifyDataSetChanged()
            }
    }
}
