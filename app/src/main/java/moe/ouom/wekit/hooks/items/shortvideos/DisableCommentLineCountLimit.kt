package moe.ouom.wekit.hooks.items.shortvideos

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "视频号/禁用评论行数限制")
class DisableCommentLineCountLimit : BaseSwitchFunctionHookItem() {

    override fun entry(classLoader: ClassLoader) {
        "com.tencent.mm.plugin.finder.view.FinderCommentFooter".toClass(classLoader)
            .asResolver().apply {
                firstMethod { name = "getCommentTextLimit" }
                    .hookBefore { param ->
                        param.result = Int.MAX_VALUE
                    }

                firstMethod { name = "getCommentTextLimitStart" }
                    .hookBefore { param ->
                        param.result = Int.MAX_VALUE
                    }

                firstMethod { name = "getCommentTextLineLimit" }
                    .hookBefore { param ->
                        param.result = Int.MAX_VALUE
                    }
            }
    }
}