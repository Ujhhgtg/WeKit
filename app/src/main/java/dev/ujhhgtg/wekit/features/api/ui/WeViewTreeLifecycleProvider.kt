package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner

@Feature(
    id = "Compose 生命周期提供方",
    nameRes = "feature_we_view_tree_lifecycle_provider_name",
    categoryIds = [FeatureCategoryIds.API],
)
object WeViewTreeLifecycleProvider : ApiFeature() {

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
