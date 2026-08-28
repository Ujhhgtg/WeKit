package dev.ujhhgtg.wekit.features.items.shortvideos

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "禁用评论长度限制",
    nameRes = "feature_disable_comment_size_limit_name",
    categoryIds = [FeatureCategoryIds.CHANNELS],
    descriptionRes = "feature_disable_comment_size_limit_description",
)
object DisableCommentSizeLimit : SwitchFeature() {

    override fun onEnable() {
        "com.tencent.mm.plugin.finder.view.FinderCommentFooter".toClass()
            .reflekt().apply {
                firstMethod { name = "getCommentTextLimit" }
                    .hookBefore {
                        result = 9999
                    }

                runCatching {
                    firstMethod { name = "getCommentTextLimitStart" }
                        .hookBefore {
                            result = 9999
                        }
                }

                firstMethod { name = "getCommentTextLineLimit" }
                    .hookBefore {
                        result = 9999
                    }
            }
    }
}
