package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    id = "显示微信 ID",
    nameRes = "feature_show_wx_id_in_contact_details_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS],
    descriptionRes = "feature_show_wx_id_in_contact_details_description",
)
object ShowWxIdInContactDetails : SwitchFeature(), IContactInfoProvider {

    private const val PREF_KEY = "wxid_display"

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedContactsString(
                    R.string.contacts_wechat_id_value,
                    wxId ?: activity.localizedContactsString(R.string.contacts_get_failed),
                ),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val wxId = activity.currentWxId ?: return true

        copyToClipboard(activity, wxId)
        showToast(activity, activity.localizedContactsString(R.string.contacts_copied))
        return true
    }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
