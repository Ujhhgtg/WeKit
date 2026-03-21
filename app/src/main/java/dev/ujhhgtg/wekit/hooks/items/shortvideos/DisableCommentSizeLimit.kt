package dev.ujhhgtg.wekit.hooks.items.shortvideos

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem

@HookItem(
    path = "视频号/禁用评论长度限制",
    desc = "禁用视频号发送评论的字数行数限制 (不保证有效, 云端可能有二次限制)"
)
object DisableCommentSizeLimit : SwitchHookItem() {

    override fun onEnable() {
        "com.tencent.mm.plugin.finder.view.FinderCommentFooter".toClass()
            .asResolver().apply {
                firstMethod { name = "getCommentTextLimit" }
                    .hookBefore { param ->
                        param.result = 9999
                    }

                runCatching {
                    firstMethod { name = "getCommentTextLimitStart" }
                        .hookBefore { param ->
                            param.result = 9999
                        }
                }

                firstMethod { name = "getCommentTextLineLimit" }
                    .hookBefore { param ->
                        param.result = 9999
                    }
            }
    }
}
