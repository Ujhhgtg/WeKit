package dev.ujhhgtg.wekit.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    private val methodIsTablet by dexMethod {
        matcher {
            usingEqStrings("Lenovo TB-9707F", "eebbk")
        }
    }
    private val methodIsTablet2 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }

    override fun onEnable() {
        methodIsTablet.hookBefore {
            result = true
        }

        methodIsTablet2.hookBefore {
            result = true
        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            val view = args[0] as? Button? ?: return@hookBefore
            if (view.isGone) view.isVisible = true
        }

        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
            val btn = thisObject!!.reflekt().firstField {
                type = Button::class
            }.get()!! as Button
            btn.isVisible = true
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
