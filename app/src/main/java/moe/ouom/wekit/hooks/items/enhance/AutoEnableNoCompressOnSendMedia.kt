package moe.ouom.wekit.hooks.items.enhance

import android.app.Activity
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "聊天与消息/自动启用发送原图", desc = "发送媒体时自动勾选发送原图选项")
object AutoEnableNoCompressOnSendMedia : BaseSwitchFunctionHookItem() {

    override fun entry(classLoader: ClassLoader) {
        for (clsName in setOf("com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
        )) {
            clsName.toClass(classLoader).hookBefore("onCreate") { param ->
                val activity = param.thisObject as Activity
                activity.intent.putExtra("send_raw_img", true)
            }
        }
    }
}
