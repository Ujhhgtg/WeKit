package dev.ujhhgtg.wekit.features.items.official_accounts

import android.content.ComponentName
import android.content.Intent
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger

object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override val technicalId = "恢复旧版公众号列表"
    override val nameRes = R.string.feature_use_legacy_official_accounts_view_name
    override val categoryIds = listOf(FeatureCategoryIds.OFFICIAL_ACCOUNTS)
    override val descriptionRes = R.string.feature_use_legacy_official_accounts_view_description

    private const val LEGACY_OFFICIAL_ACCOUNTS_UI =
        "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"

    private val hasLegacyOfficialAccountsUI by lazy {
        LEGACY_OFFICIAL_ACCOUNTS_UI.toClassOrNull() != null
    }

    override val declaredErrors
        get() =
            if (hasLegacyOfficialAccountsUI) emptyList()
            else listOf(R.string.feature_error_host_version_unsupported)

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className
        if (
            className ==
                "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
                className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
            intent.component = ComponentName(HostInfo.packageName, LEGACY_OFFICIAL_ACCOUNTS_UI)
        }
    }
}
