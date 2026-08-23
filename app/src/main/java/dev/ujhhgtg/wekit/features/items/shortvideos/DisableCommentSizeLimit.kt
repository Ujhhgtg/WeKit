package dev.ujhhgtg.wekit.features.items.shortvideos

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableCommentSizeLimit : SwitchFeature() {

    override val technicalId = "禁用评论长度限制"
    override val nameRes = R.string.feature_disable_comment_size_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHANNELS)
    override val descriptionRes = R.string.feature_disable_comment_size_limit_description

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
