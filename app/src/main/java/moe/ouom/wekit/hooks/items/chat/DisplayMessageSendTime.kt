package moe.ouom.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.model.MessageInfo
import moe.ouom.wekit.hooks.api.ui.WeChatMessageViewApi
import moe.ouom.wekit.utils.formatEpoch


@HookItem(path = "聊天/显示消息时间", desc = "显示精确消息发送时间")
object DisplayMessageSendTime : SwitchHookItem(),
    WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val VIEW_TAG = "wekit_message_send_time"

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View,
        chattingContext: Any,
        msgInfo: MessageInfo
    ) {
        val tag = view.tag
        val text = formatEpoch(msgInfo.createTime)

        // FIXME: method 1, bigger font size leads to clipping
        val avatar = tag.asResolver()
            .firstField {
                name = "avatarIV"
                superclass()
            }
            .get() as? View? ?: return
        val parent = avatar.parent as ViewGroup
        if (parent.findViewWithTag<TextView>(VIEW_TAG) != null) return

        val context = parent.context
        val label = TextView(context).apply {
            this.tag = VIEW_TAG
            this.text = text
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
        }
        val lp = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_TOP, avatar.id)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            topMargin = -13
        }
        parent.addView(label, lp)

        // method 2, not as elegant as method 1 so not using
//        val timeView = tag.asResolver()
//            .firstField {
//                name = "timeTV"
//                superclass()
//            }
//            .get() as? TextView? ?: return
//        timeView.text = text
//        timeView.visibility = View.VISIBLE
    }
}