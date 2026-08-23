package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Info
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast

object DisplayMessageDetails : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "显示消息详情"
    override val nameRes = R.string.feature_display_message_details_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_message_details_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777005, localizedChatString(R.string.chat_message_details_menu),
                ChatInfoIcon, MaterialSymbols.Outlined.Info, { _ -> true },
                // per-message detail dialog; has no meaning for a batch selection
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            )
            { view, _, msgInfo ->
                val displayItems = mutableListOf<Pair<String, String>>()
                displayItems += view.context.localizedChatString(R.string.chat_message_details_type) to
                    msgInfo.typeCode.toString()
                displayItems += "ID" to msgInfo.id.toString()
                displayItems += view.context.localizedChatString(R.string.chat_message_details_talker_id) to
                    msgInfo.talker
                displayItems += view.context.localizedChatString(R.string.chat_message_details_sender_id) to
                    msgInfo.sender
                displayItems += view.context.localizedChatString(R.string.chat_message_details_content) to
                    msgInfo.content

                showComposeDialog(view.context) {
                    val localizedContext = LocalWeKitLocalizedContext.current
                    AlertDialogContent(
                        title = { Text(stringResource(R.string.chat_message_details_title)) },
                        text = {
                            LazyColumn {
                                lazySegmentedItems(displayItems, key = { it.first }) { (key, value) ->
                                    BaseWidget(
                                        title = key,
                                        description = value,
                                        onClick = {
                                            copyToClipboard(value)
                                            showToast(localizedContext.getString(R.string.chat_message_details_copied))
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                    )
                }
            }
        )
    }
}
