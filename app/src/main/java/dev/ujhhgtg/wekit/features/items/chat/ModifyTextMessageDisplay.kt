package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object ModifyTextMessageDisplay : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "修改文本消息显示"
    override val nameRes = R.string.feature_modify_text_message_display_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_modify_text_message_display_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777002,
                localizedChatString(R.string.chat_modify_text_menu),
                EditIcon,
                MaterialSymbols.Outlined.Edit,
                { msgInfo -> msgInfo.type?.isText ?: false },
                // operates on the single message's own View; can't apply to a batch
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, _ ->
                showComposeDialog(view.context) {
                    var input by remember {
                        mutableStateOf(
                            view.reflekt()
                                .firstField {
                                    type = CharSequence::class
                                    superclass()
                                }.get().toString()
                        )
                    }

                    AlertDialogContent(
                        title = { Text(stringResource(R.string.chat_modify_text_title)) },
                        text = {
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                label = { Text(stringResource(R.string.chat_modify_text_content)) })
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                view.reflekt()
                                    .firstMethod {
                                        parameters(CharSequence::class)
                                    }
                                    .invoke(input)
                                onDismiss()
                            }) {
                                Text(stringResource(R.string.dialog_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { onDismiss() }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        })
                }
            }
        )
    }
}
