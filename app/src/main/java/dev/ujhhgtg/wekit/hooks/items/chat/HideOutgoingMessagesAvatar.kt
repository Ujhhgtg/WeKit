package dev.ujhhgtg.wekit.hooks.items.chat

import android.view.View
import android.view.ViewGroup
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import java.lang.reflect.Field

@HookItem(path = "聊天/隐藏发送消息头像", description = "隐藏自己发出的消息的用户头像 (Telegram 风格)")
object HideOutgoingMessagesAvatar : SwitchHookItem(), WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private lateinit var avatarViewField: Field

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val tag = view.tag
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.isSend == 0) return

        if (!::avatarViewField.isInitialized) {
            avatarViewField = tag.asResolver()
                .firstField {
                    name = "avatarIV"
                    superclass()
                }.self
        }

        val avatar = avatarViewField.get(tag) as? View? ?: return
        (avatar.parent as View).apply {
            val view = parent as ViewGroup
            (view.layoutParams as? ViewGroup.MarginLayoutParams? ?: return).rightMargin = 20
            view.requestLayout()
            visibility = View.GONE
        }
    }
}
