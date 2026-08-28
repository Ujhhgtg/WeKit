package dev.ujhhgtg.wekit.features.api.ui

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds

@Feature(
    id = "微信主屏幕美化服务",
    nameRes = "feature_we_main_activity_beautify_api_name",
    categoryIds = [FeatureCategoryIds.API],
    descriptionRes = "feature_we_main_activity_beautify_api_description",
)
object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    val methodDoOnCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        }
    }
}
