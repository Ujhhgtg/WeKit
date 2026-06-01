package dev.ujhhgtg.wekit.hooks.items.payment

import android.content.Context
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "红包与支付/修改显示余额", description = "伪装钱包余额文字")
object ModifyWalletBalanceDisplay : ClickableHookItem(), IResolvesDex {

    private const val KEY_BALANCE = "fake_wallet_balance"

    private val methodUpdateBalanceDisplay by dexMethod()
    private val methodTickerViewSetText by dexMethod()

    override fun onEnable() {
        methodUpdateBalanceDisplay.hookAfter {
            val text = WePrefs.getStringOrDef(KEY_BALANCE, null) ?: return@hookAfter
            val balanceView = thisObject.asResolver()
                .firstField { type = TextView::class }
                .get()!! as TextView
            balanceView.text = text
        }

        methodTickerViewSetText.hookBefore {
            args[0] = WePrefs.getStringOrDef(KEY_BALANCE, null) ?: return@hookBefore
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodUpdateBalanceDisplay.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
                usingEqStrings("MicorMsg.MallIndexUIv2", "updateBalanceNum")
            }
        }

        methodTickerViewSetText.find(dexKit) {
            matcher {
                // TickerView is only used for displaying balance
                declaredClass = "com.robinhood.ticker.TickerView"
                usingEqStrings("Need to call #setCharacterLists first.")
            }
        }
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
