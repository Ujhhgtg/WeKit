package moe.ouom.wekit.hooks.items.shortvideos

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(
    path = "视频号/禁用评论长度限制",
    desc = "禁用视频号发送评论的字数行数限制 (不保证有效, 云端可能有二次限制)"
)
class DisableCommentSizeLimit : SwitchHookItem() {

    override fun onLoad() {
        "com.tencent.mm.plugin.finder.view.FinderCommentFooter".toClass()
            .asResolver().apply {
                firstMethod { name = "getCommentTextLimit" }
                    .hookBefore { param ->
                        param.result = 9999
                    }

                firstMethod { name = "getCommentTextLimitStart" }
                    .hookBefore { param ->
                        param.result = 9999
                    }

                firstMethod { name = "getCommentTextLineLimit" }
                    .hookBefore { param ->
                        param.result = 9999
                    }
            }
    }
}