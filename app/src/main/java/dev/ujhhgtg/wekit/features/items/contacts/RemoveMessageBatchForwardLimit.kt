package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object RemoveMessageBatchForwardLimit : SwitchFeature() {

    override val technicalId = "移除消息批量转发限制"
    override val nameRes = R.string.feature_remove_message_batch_forward_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_remove_message_batch_forward_limit_description

    private const val TAG = "RemoveMessageBatchForwardLimit"

    override fun onEnable() {
        listOf(
            "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
            "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        ).forEach {
            it.toClassOrNull()?.hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("max_limit_num", 999)
                WeLogger.i(TAG, "removed batch forward limit for $it")
            }
        }
    }
}
