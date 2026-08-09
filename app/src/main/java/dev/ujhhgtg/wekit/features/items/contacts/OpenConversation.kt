package dev.ujhhgtg.wekit.features.items.contacts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    id = "跳转对话",
    nameRes = "feature_open_conversation_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_open_conversation_description",
)
object OpenConversation : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showOpenConversationDialog(context)
    }
}

fun showOpenConversationDialog(context: Context) {
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
                    if (wxId.isBlank()) {
                        showToast(context, "微信 ID 为空!")
                        return@TextButton
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
                }) { Text("好友主页") }

                TextButton(onClick = {
                    if (wxId.isBlank()) {
                        showToast(context, "微信 ID 为空!")
                        return@TextButton
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.SETTINGS)
                }) { Text("好友设置") }

                Button(onClick = {
                    if (wxId.isBlank()) {
                        showToast(context, "微信 ID 为空!")
                        return@Button
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.CONVERSATION)
                }) { Text("对话") }
            })
    }
}
