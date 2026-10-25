package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.find
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableShareScreenshotToast : SwitchFeature(), IResolveDex {
    override val technicalId = "禁用「转发截图」提示"
    override val nameRes = R.string.feature_disable_share_screenshot_toast_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_share_screenshot_toast_description

    private val methodDisplayToast by dexMethod()

    override fun resolveDex() {
        // Both renderers coexist on some hosts; keep the tongue hook while it is available.
        if (
            methodDisplayToast.find(allowFailure = true) {
                searchPackages("com.tencent.mm.ui.feature.api.screenshot")
                usingEqStrings(
                    "MicroMsg.ScreenShotShareService",
                    "showShareTongue, shareTongue already showing, reset onClick & countDown",
                )
            }
        )
            return

        methodDisplayToast.find {
            searchPackages("com.tencent.mm.ui.feature.api.screenshot")
            returnType = "boolean"
            paramCount = 2
            usingEqStrings(
                "MicroMsg.ScreenShotShareService",
                "initImageBottomSheet, getTopActivity failed",
                "initImageBottomSheet, getOriImgPath not exists",
            )
        }
    }

    override fun onEnable() {
        // The bottom-sheet initializer returns success; the older tongue renderer returns void.
        val suppressedResult =
            if (methodDisplayToast.method.returnType == Boolean::class.javaPrimitiveType) false
            else null
        methodDisplayToast.hookBefore {
            result = suppressedResult
        }
    }
}
