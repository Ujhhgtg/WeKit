package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object ForceTabletMode : SwitchFeature(), IResolveDex {

    override val technicalId = "强制平板模式"
    override val nameRes = R.string.feature_force_tablet_mode_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_force_tablet_mode_description

    private val methodIsTablet by dexMethod {
        matcher {
            usingEqStrings("Lenovo TB-9707F", "eebbk")
        }
    }
//    private val methodIsTablet2 by dexMethod {
//        matcher {
//            usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
//        }
//    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }
//    private val methodCgiCheckLoginAsPad by dexMethod {
//        matcher {
//            usingEqStrings("MicroMsg.CgiCheckLoginAsPad", "/cgi-bin/micromsg-bin/checkloginaspad")
//        }
//    }

    override fun onEnable() {
        methodIsTablet.hookAfter {
            result = !Throwable().stackTraceToString().contains("com.tencent.mm.pluginsdk.ui.chat")
        }

//        methodIsTablet2.hookBefore {
//            result = true
//        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            val view = args[0] as? Button? ?: return@hookBefore
            if (view.isGone) view.isVisible = true
        }

//        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
//            val btn = thisObject!!.reflekt().firstField {
//                type = Button::class
//            }.get()!! as Button
//            btn.isVisible = true
//        }

//        methodCgiCheckLoginAsPad.hookBefore {
//            result = true
//        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = stringResource(R.string.warning)) },
                    text = { Text(text = stringResource(R.string.system_risky_feature_warning)) },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
