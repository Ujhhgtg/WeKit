package dev.ujhhgtg.wekit.features.items.chat

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.utils.fastJavaMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "禁用输入框快捷语音转文字",
    nameRes = "feature_disable_speech_to_text_button_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_disable_speech_to_text_button_description",
)
object DisableSpeechToTextButton : SwitchFeature() {

    override fun onEnable() {
        ChatFooter::getV2TBtnLayout.fastJavaMethod!!.hookBefore {
            result = null
        }
    }
}
