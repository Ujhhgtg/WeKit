package dev.ujhhgtg.wekit.features.items.payment

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.nul
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool

@Feature(
    id = "修改显示余额",
    nameRes = "feature_modify_wallet_balance_display_name",
    categoryIds = [FeatureCategoryIds.PAYMENT],
    descriptionRes = "feature_modify_wallet_balance_display_description",
)
object ModifyWalletBalanceDisplay : ClickableFeature(), IResolveDex {

    private const val KEY_BALANCE = "fake_wallet_balance"

    private val methodWcPayMoneyLoadingViewSetMoneyCore by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            paramTypes(BString, bool, bool, bool)
            addInvoke {
                declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
                name = "setFirstMoney"
            }
        }
    }

    private var balance by prefOption(KEY_BALANCE, nul<String>())

    override fun onEnable() {
        methodWcPayMoneyLoadingViewSetMoneyCore.hookBefore {
            val balance = balance ?: return@hookBefore
            args[0] = balance
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var balanceInput by remember { mutableStateOf(balance ?: "") }

            AlertDialogContent(
                title = { Text("修改显示余额") },
                text = {
                    TextField(
                        value = balanceInput,
                        onValueChange = { balanceInput = it },
                        label = { Text("零钱余额 (留空不修改)") })
                },
                confirmButton = {
                    Button(onClick = {
                        balance = if (!balanceInput.isBlank())
                            balanceInput
                        else
                            null
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
