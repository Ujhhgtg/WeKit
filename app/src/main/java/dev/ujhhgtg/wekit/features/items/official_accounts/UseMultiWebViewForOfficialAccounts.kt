package dev.ujhhgtg.wekit.features.items.official_accounts

import android.content.Intent
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "允许公众号网页多开",
    nameRes = "feature_use_multi_web_view_for_official_accounts_name",
    categoryIds = [FeatureCategoryIds.OFFICIAL_ACCOUNTS],
    descriptionRes = "feature_use_multi_web_view_for_official_accounts_description",
)
object UseMultiWebViewForOfficialAccounts : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    private const val tag = "UseMultiWebViewForOfficialAccounts"

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className ?: return
        if (!className.endsWith(".ui.timeline.preload.ui.TmplWebViewMMUI")) return

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        WeLogger.d(tag, "enabled multi webview for $className")
    }
}
