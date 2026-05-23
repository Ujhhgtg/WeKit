package dev.ujhhgtg.wekit.hooks.items.contacts

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@HookItem(path = "联系人与群组/跳转对话", description = "打开指定微信 ID 的对话/好友主页/好友设置界面")
object OpenConversation : ClickableHookItem() {

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var wxId by remember { mutableStateOf("") }
            AlertDialogContent(
                title = { Text("跳转对话") },
                text = {
                    TextField(
                        value = wxId,
                        onValueChange = { wxId = it },
                        label = { Text("微信 ID") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
                    }) { Text("好友主页") }
                    TextButton(onClick = {
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.SETTINGS)
                    }) { Text("好友设置") }
                    Button(onClick = {
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.CONVERSATION)
                    }) { Text("对话") }
                })
        }
    }
}
