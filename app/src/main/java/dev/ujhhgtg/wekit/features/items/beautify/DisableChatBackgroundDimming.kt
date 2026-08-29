package dev.ujhhgtg.wekit.features.items.beautify

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableChatBackgroundDimming : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用聊天背景压暗"
    override val nameRes = R.string.feature_disable_chat_background_dimming_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_disable_chat_background_dimming_description

    private val methodSetChatBackground by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingUI.ChattingBackgroundComponent",
                "initBackground: info:%s bgId:%s",
            )
        }
    }

    override fun onEnable() {
        methodSetChatBackground.hookAfter {
            // 遮罩与背景图是该组件仅有的两个 ImageView 字段, 遮罩的特征是背景为
            // 纯 #99000000 的 ColorDrawable (来自聊天背景布局 XML)。字段在纯色背景
            // 等路径下可能尚未赋值, 找不到遮罩即无事可做。
            thisObject!!.reflekt().fields {
                type = ImageView::class
            }.forEach { field ->
                val view = field.get() as? ImageView ?: return@forEach
                if ((view.background as? ColorDrawable)?.color == DIM_OVERLAY_COLOR) {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private const val DIM_OVERLAY_COLOR = 0x99000000.toInt()
}
