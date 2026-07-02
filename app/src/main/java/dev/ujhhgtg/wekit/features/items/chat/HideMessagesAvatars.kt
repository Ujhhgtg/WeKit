package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Feature(name = "隐藏消息头像", categories = ["聊天"], description = "隐藏消息的用户头像 (Telegram 风格)")
object HideMessagesAvatars : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    var hideIncoming by prefOption("chat_hide_avatar_incoming", true)
    private var hideOutgoing by prefOption("chat_hide_avatar_outgoing", false)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val tag = view.tag
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        if (msgInfo.isSelfSender) {
            if (!hideOutgoing) return
        } else {
            if (msgInfo.isInGroupChat || !hideIncoming) return
        }

        val avatar = tag.reflekt()
            .firstField {
                name = "avatarIV"
                superclass()
            }.get() as? View? ?: return

        if (msgInfo.type == MessageType.VIDEO) {
            avatar.visibility = View.GONE
            return
        }

        if (msgInfo.type == MessageType.VOICE) {
            avatar.visibility = View.GONE
            return
        }

        (avatar.parent as ViewGroup).apply {
            visibility = View.GONE
            val lp = layoutParams as ViewGroup.MarginLayoutParams
            if (msgInfo.isSelfSender) lp.rightMargin = 20 else lp.leftMargin = 20
            requestLayout()
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var incoming by remember { mutableStateOf(hideIncoming) }
            var outgoing by remember { mutableStateOf(hideOutgoing) }

            AlertDialogContent(
                title = { Text("隐藏消息头像") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("隐藏私聊对方头像") },
                            supportingContent = { Text("仅在私聊中隐藏对方的用户头像") },
                            trailingContent = { Switch(checked = incoming, onCheckedChange = { incoming = it }) },
                            modifier = Modifier.clickable { incoming = !incoming }
                        )
                        ListItem(
                            headlineContent = { Text("隐藏发送消息头像") },
                            supportingContent = { Text("隐藏自己发出的消息的用户头像") },
                            trailingContent = { Switch(checked = outgoing, onCheckedChange = { outgoing = it }) },
                            modifier = Modifier.clickable { outgoing = !outgoing }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        hideIncoming = incoming
                        hideOutgoing = outgoing
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
