package dev.ujhhgtg.wekit.features.items.chat

import android.view.ViewGroup
import android.widget.FrameLayout
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.removeSelf
import dev.ujhhgtg.wekit.utils.android.constructor

@Feature(
    id = "禁用输入框快捷语音转文字",
    nameRes = "feature_disable_speech_to_text_button_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_disable_speech_to_text_button_description",
)
object DisableSpeechToTextButton : SwitchFeature() {

    override fun onEnable() {
        ChatFooter::class.constructor.hookAfter {
            val chatFooter = thisObject as ChatFooter
            val button = chatFooter.findViewWhich { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.SpeechInputLayout" }!! as FrameLayout
            (((button.parent as ViewGroup).parent as ViewGroup).parent as ViewGroup).removeSelf()
        }
    }
}
