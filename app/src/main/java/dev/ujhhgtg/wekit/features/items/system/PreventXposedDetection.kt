package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo

@Feature(
    id = "禁止微信检测 Xposed",
    nameRes = "feature_prevent_xposed_detection_name",
    categoryIds = [FeatureCategoryIds.SYSTEM_PRIVACY],
    descriptionRes = "feature_prevent_xposed_detection_description",
)
object PreventXposedDetection : SwitchFeature(), IResolveDex {

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings(
                "de.robv.android.xposed.XposedBridge",
                "com.zte.heartyservice.SCC.FrameworkBridge"
            )
        }
    }

    override fun onEnable() {
        if (methodCheckStackTraceElements.isPlaceholder || HostInfo.isHostGooglePlay) return

        methodCheckStackTraceElements.hookBefore {
            result = false
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_prevent_xposed_detection_name)) },
                    text = {
                        Text(stringResource(R.string.system_prevent_xposed_google_play_warning))
                    },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } })
            }
            return false
        }

        return true
    }
}
