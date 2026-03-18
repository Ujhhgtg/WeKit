package moe.ouom.wekit.hooks.items.system

import android.content.Context
import androidx.compose.material3.Text
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/强制平板模式", desc = "让应用将当前设备识别为平板")
object ForceTabletMode : SwitchHookItem(), IResolvesDex {

    private val methodIsTablet by dexMethod()

    override fun onEnable() {
        methodIsTablet.hookBefore { param ->
            param.result = true
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodIsTablet.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("Lenovo TB-9707F", "eebbk")
            }
        }

        return descriptors
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
                            dismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(dismiss) {
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