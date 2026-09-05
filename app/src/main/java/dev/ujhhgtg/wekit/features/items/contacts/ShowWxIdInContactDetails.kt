package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeContactHeaderApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.currentWxId

object ShowWxIdInContactDetails : SwitchFeature(), WeContactHeaderApi.Provider {

    override val technicalId = "显示微信 ID"
    override val nameRes = R.string.feature_show_wx_id_in_contact_details_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_wx_id_in_contact_details_description

    override fun getHeaderText(activity: Activity): String = activity.localizedContactsString(
        R.string.contacts_wechat_id_value,
        activity.currentWxId ?: activity.localizedContactsString(R.string.contacts_get_failed),
    )

    override fun onEnable() = WeContactHeaderApi.addProvider(this)

    override fun onDisable() = WeContactHeaderApi.removeProvider(this)
}
