package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "移除媒体发送数量限制",
    nameRes = "feature_remove_send_media_count_limit_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_remove_send_media_count_limit_description",
)
object RemoveSendMediaCountLimit : SwitchFeature() {

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
