package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object AutoEnableSendOriginalMedia : SwitchFeature() {

    override val technicalId = "自动启用发送原图"
    override val nameRes = R.string.feature_auto_enable_send_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_original_media_description

    /**
     * The 8.0.78+ chat picker (the half-screen album, A/B tested via
     * clicfg_local_media_picker_chatting_opt) keeps its initial original-image
     * state in this feature-arguments object instead of an Activity extra. Older hosts do
     * not have the class, so the optional reflection hook is deliberately
     * resolved at runtime.
     */
    private const val LOCAL_PICKER_ARGUMENTS =
        "com.tencent.mm.plugin.picker.scene.chatting.ChattingLocalMediaPickerFeatureArguments"
    private const val MEDIA_TAB_ALBUM_UI = "com.tencent.mm.plugin.gallery.ui.MediaTabAlbumUI"

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

        // Both picker state holders read this getter once at startup, so the
        // user can still untick the option afterwards. Hooking the getter
        // avoids depending on the constructor signature.
        LOCAL_PICKER_ARGUMENTS.toClassOrNull()
            ?.reflekt()
            ?.firstMethod {
                name = "getInitialSendOriginal"
                parameters()
            }
            ?.hookBefore {
                result = true
            }

        // MediaTabPickerUI embeds MediaTabAlbumUI as a VAS fragment, so its
        // Activity onCreate hook is not reached. Its inherited initView still
        // reads the same extras before constructing the picker state.
        MEDIA_TAB_ALBUM_UI.toClassOrNull()
            ?.reflekt()
            ?.firstMethodOrNull { name = "initView" }
            ?.hookBefore {
                val activity = thisObject as Activity
                activity.intent.putExtra("send_raw_img", true)
                activity.intent.putExtra("key_send_raw_image", true)
            }
    }
}
