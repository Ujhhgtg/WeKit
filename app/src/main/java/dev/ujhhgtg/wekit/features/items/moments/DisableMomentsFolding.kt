package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableMomentsFolding : SwitchFeature() {
    override val technicalId = "禁用朋友圈折叠"
    override val nameRes = R.string.feature_disable_moments_folding_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_disable_moments_folding_description

    private val methodGetExperiment by dexMethod {
        returnType = "java.lang.String"
        paramTypes("java.lang.String", "java.lang.String", "boolean")
        usingEqStrings("V2GetExpt result[%s] exptId[%d] key[%s] defStr[%s] cost[%d]")
    }

    override fun onEnable() {
        methodGetExperiment.hookBefore {
            if (args[0] == "clicfg_sns_ws_un_fold_total_switch") result = "1"
        }
    }
}
