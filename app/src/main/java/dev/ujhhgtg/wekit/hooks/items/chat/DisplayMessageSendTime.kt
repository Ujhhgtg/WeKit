package dev.ujhhgtg.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.reflection.asResolver


@HookItem(path = "聊天/显示消息时间", description = "显示精确消息发送时间")
object DisplayMessageSendTime : SwitchHookItem(),
    WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val VIEW_TAG = "wekit_message_send_time"
    private var clippingDisabled = false

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val tag = view.tag
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val text = formatEpoch(msgInfo.createTime)

        val avatar = tag.asResolver()
            .firstField {
                name = "avatarIV"
                superclass()
            }
            .get() as? View? ?: return
        val parent = avatar.parent as ViewGroup
        if (parent.findViewWithTag<TextView>(VIEW_TAG) != null) return

        val context = parent.context
        val color = if (context.isDarkMode) {
            "#9E9E9E".toColorInt()
        } else {
            "#616161".toColorInt()
        }
        val label = TextView(context).apply {
            this.tag = VIEW_TAG
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(color)
        }
        val lp = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_TOP, avatar.id)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            topMargin = -24
        }
        parent.addView(label, lp)

        parent.post {
            if (!clippingDisabled) {
                var p = avatar.parent as? ViewGroup
                while (p != null) {
                    p.clipChildren = false
                    p.clipToPadding = false
                    p = p.parent as? ViewGroup
                }
                clippingDisabled = true
            }
        }
    }
}
