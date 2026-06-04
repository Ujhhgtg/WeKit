package dev.ujhhgtg.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.reflection.asResolver


@HookItem(path = "聊天/显示消息时间", description = "显示精确消息发送时间")
object DisplayMessageSendTime : ClickableHookItem(),
    WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val tag = view.tag
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val text = formatEpoch(msgInfo.createTime, pattern)

        val time = tag.asResolver()
            .firstField {
                name = "timeTV"
                superclass()
            }
            .get() as? TextView? ?: return

        time.visibility = View.VISIBLE
        time.text = text
        val parent = time.parent as ViewGroup
        parent.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
    }

    private var pattern by prefOption("msg_time_pattern", "yyyy/MM/dd HH:mm:ss")

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var input by remember { mutableStateOf(pattern) }

            AlertDialogContent(title = { Text("显示消息时间") },
                text = {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("时间格式 (Java)") })
                },
                confirmButton = {
                    Button(onClick = {
                        pattern = input
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
        }
    }
}
