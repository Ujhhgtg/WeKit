package dev.ujhhgtg.wekit.features.items.miniapps

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcesses

object BypassUnderageGamingLimit : SwitchFeature() {

    override val technicalId = "绕过防沉迷"
    override val nameRes = R.string.feature_bypass_underage_gaming_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_bypass_underage_gaming_limit_description

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        listOf(
            "com.tencent.xweb.pinus.PSWebview",
            "com.tencent.xweb.pinus.sdk.WebView",
            "com.tencent.xweb.WebView"
        ).forEach {
            it.toClassOrNull()?.reflekt()?.firstMethod("loadUrl")?.hookBefore {
                val url = args[0] as String
                val webView = thisObject as View

                if (url.startsWith("https://jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html?")) {
                    webView.translationX = 99999.0f
                    webView.translationY = 99999.0f
                    webView.scaleX = 0.01f
                    webView.scaleY = 0.01f
                }
            }
        }
    }
}
