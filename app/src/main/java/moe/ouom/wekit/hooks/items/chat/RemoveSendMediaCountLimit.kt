package moe.ouom.wekit.hooks.items.chat

import android.app.Activity
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "聊天/移除媒体发送数量限制", desc = "移除发送媒体的数量限制")
object RemoveSendMediaCountLimit : SwitchHookItem() {

    private val HOOKED_CLASS_NAMES = listOf(
        "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
    )

    override fun onLoad() {
        for (clsName in HOOKED_CLASS_NAMES) {
            clsName.toClass().hookBefore("onCreate") { param ->
                val activity = param.thisObject as Activity
                activity.intent.putExtra("max_select_count", 999)
            }
        }
    }
}
