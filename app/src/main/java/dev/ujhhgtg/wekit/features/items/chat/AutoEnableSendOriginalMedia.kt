package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "自动启用发送原图",
    nameRes = "feature_auto_enable_send_original_media_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_auto_enable_send_original_media_description",
)
object AutoEnableSendOriginalMedia : SwitchFeature() {

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("send_raw_img", true)
            }
        }
    }
}
