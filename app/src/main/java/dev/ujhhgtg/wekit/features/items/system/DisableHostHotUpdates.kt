package dev.ujhhgtg.wekit.features.items.system

import android.annotation.SuppressLint
import android.content.ComponentName
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.setEnabled
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

object DisableHostHotUpdates : SwitchFeature() {

    override val technicalId = "禁用微信热更新"
    override val nameRes = R.string.feature_disable_host_hot_updates_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_host_hot_updates_description

    private val componentNames = listOf(
        "com.tencent.tinker.lib.service.TinkerPatchForeService",
        "com.tencent.tinker.lib.service.TinkerPatchService",
        $$"com.tencent.tinker.lib.service.TinkerPatchService$InnerService",
        "com.tencent.tinker.lib.service.DefaultTinkerResultService",
    )

    @SuppressLint("SdCardPath")
    @OptIn(ExperimentalPathApi::class)
    override fun onEnable() {
        runCatching { Path("/data/data/${HostInfo.packageName}/tinker").deleteRecursively() }

        ShareTinkerInternals::class.reflekt()
            .methods {
                name {
                    it.startsWith("isTinkerEnabled")
                }
            }
            .forEach {
                it.hookBefore {
                    result = false
                }
            }

        batchSetEnabled(false)
    }

    override fun onDisable() {
        batchSetEnabled(true)
    }

    private fun batchSetEnabled(enabled: Boolean) {
        HostInfo.application.apply {
            componentNames.forEach {
                runCatching {
                    ComponentName(
                        this,
                        it
                    ).setEnabled(this, enabled)
                }.onFailure { WeLogger.e(TAG, "failed to set $enabled state for $it") }
            }
        }
    }

    private const val TAG = "DisableHostHotUpdates"
}
