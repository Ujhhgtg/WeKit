package dev.ujhhgtg.wekit.features.items.official_accounts

import android.content.ComponentName
import android.content.Intent
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "恢复旧版公众号列表",
    nameRes = "feature_use_legacy_official_accounts_view_name",
    categoryIds = [FeatureCategoryIds.OFFICIAL_ACCOUNTS],
    descriptionRes = "feature_use_legacy_official_accounts_view_description",
)
object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
            intent.component = ComponentName(
                HostInfo.packageName,
                "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"
            )
        }
    }
}
