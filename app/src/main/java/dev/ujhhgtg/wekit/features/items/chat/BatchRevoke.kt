package dev.ujhhgtg.wekit.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(name = "批量撤回", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可批量撤回消息")
object BatchRevoke : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777023, "撤回", EditIcon, MaterialSymbols.Outlined.Edit,
                isSupported = { false },
                // revokes then loads one message's text into the input box; single-message only
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { true },
                    onClick = { view, _, msgs ->
                        // yeah i know this is very cursed; this is the sequelae of writing too much python
                        val succeeded = msgs.sumOf { msg ->
                            runCatching { WeMessageApi.revokeMsg(msg); 1 }
                                .getOrElse {
                                    WeLogger.e("BatchRevoke", "failed to revoke msgId=${msg.id}", it); 0
                                }
                        }
                        showToast(view.context, "已撤回 $succeeded/${msgs.size} 条消息")
                    }
                )
            ) { _, _, _ ->
            }
        )
    }
}
