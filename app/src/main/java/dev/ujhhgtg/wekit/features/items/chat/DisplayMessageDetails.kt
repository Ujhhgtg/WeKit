package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Info
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    id = "显示消息详情",
    nameRes = "feature_display_message_details_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_display_message_details_description",
)
object DisplayMessageDetails : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777005, "查看详情",
                ChatInfoIcon, MaterialSymbols.Outlined.Info, { _ -> true },
                // per-message detail dialog; has no meaning for a batch selection
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            )
            { view, _, msgInfo ->
                val displayItems = mutableListOf<Pair<String, String>>()
                displayItems += "类型" to msgInfo.typeCode.toString()
                displayItems += "ID" to msgInfo.id.toString()
                displayItems += "对方/群聊 ID" to msgInfo.talker
                displayItems += "发送者 ID" to msgInfo.sender
                displayItems += "内容" to msgInfo.content

                showComposeDialog(view.context) {
                    AlertDialogContent(
                        title = { Text("消息详情") },
                        text = {
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                            ) {
                                items(displayItems) { (key, value) ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            copyToClipboard(value)
                                            showToast("已复制")
                                        },
                                        supportingContent = { Text(value) },
                                        content = { Text(key) },
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onDismiss) { Text("关闭") } }
                    )
                }
            }
        )
    }
}
