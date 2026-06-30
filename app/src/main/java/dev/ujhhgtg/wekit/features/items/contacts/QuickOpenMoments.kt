package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.CameraIcon
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

@Feature(name = "快捷打开朋友圈", categories = ["联系人与群组"], description = "在首页对话列表长按菜单添加菜单项, 可点击一键跳转到朋友圈")
object QuickOpenMoments : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777018,
                text = "朋友圈",
                drawable = CameraIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() &&
                        !talker.isGroupChatWxId &&
                        !talker.startsWith("gh_") &&
                        !talker.endsWith("@app")
                },
            ) { context ->
                WeApi.openMoments(context.activity, context.talker)
            }
        )
    }
}
