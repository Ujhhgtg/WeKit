package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object RemoveSendMediaCountLimit : SwitchFeature() {

    override val technicalId = "移除媒体发送数量限制"
    override val nameRes = R.string.feature_remove_send_media_count_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_send_media_count_limit_description

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("max_select_count", 999)
            }
        }
    }
}
