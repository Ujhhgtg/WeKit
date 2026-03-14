package moe.ouom.wekit.hooks.items.payment

import android.content.Context
import android.widget.TextView
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.config.WePrefs
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "红包与支付/修改显示余额", desc = "伪装钱包余额文字")
object ModifyWalletBalanceDisplay : ClickableHookItem(), IDexFind {

    private const val KEY_BALANCE = "fake_wallet_balance"

    private val methodUpdateBalanceDisplay by dexMethod()
    private val methodTickerViewSetText by dexMethod()

    override fun onLoad() {
        methodUpdateBalanceDisplay.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val text = WePrefs.getStringOrDef(KEY_BALANCE, null) ?: return@afterIfEnabled
                    val balanceView = param.thisObject.asResolver()
                        .firstField { type = TextView::class }
                        .get()!! as TextView
                    balanceView.text = text
                }
            }
        }

        methodTickerViewSetText.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.args[0] =
                        WePrefs.getStringOrDef(KEY_BALANCE, null) ?: return@beforeIfEnabled
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodUpdateBalanceDisplay.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
                usingEqStrings("MicorMsg.MallIndexUIv2", "updateBalanceNum")
            }
        }

        methodTickerViewSetText.find(dexKit, descriptors) {
            matcher {
                // TickerView is only used for displaying balance
                declaredClass = "com.robinhood.ticker.TickerView"
                usingEqStrings("Need to call #setCharacterLists first.")
            }
        }

        return descriptors
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var input by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef(KEY_BALANCE, null) ?: ""
                )
            }

            AlertDialogContent(
                title = { Text("修改显示余额") },
                text = {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("零钱余额 (留空不修改)") })
                },
                confirmButton = {
                    Button(onClick = {
                        if (!input.isBlank())
                            WePrefs.putString(KEY_BALANCE, input)
                        else
                            WePrefs.remove(KEY_BALANCE)
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }
}