package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止自动播放视频"
    override val nameRes = R.string.feature_disable_videos_auto_play_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_disable_videos_auto_play_description

    private val methodCheckAutoPlay by dexMethod {
        matcher {
            usingEqStrings(
                "checkAutoPlay",
                "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil"
            )
        }
    }

    private val methodImproveAutoPlayInvoke by dexMethod {
        matcher {
            usingEqStrings(
                "invoke",
                $$"com.tencent.mm.plugin.sns.ui.improve.util.ImproveAutoPlayManager$autoPlay$2"
            )
        }
    }

    override fun onEnable() {
        methodCheckAutoPlay.hookBefore {
            result = false
        }
        methodImproveAutoPlayInvoke.hookBefore {
            result = false
        }
    }
}
