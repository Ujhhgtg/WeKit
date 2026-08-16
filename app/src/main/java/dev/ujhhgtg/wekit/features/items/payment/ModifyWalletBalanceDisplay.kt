package dev.ujhhgtg.wekit.features.items.payment

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
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
                title = { Text(stringResource(R.string.feature_modify_wallet_balance_display_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.payment_wallet_balance_optional),
                            ) {
                                OutlinedTextField(
                                    value = balanceInput,
                                    onValueChange = { balanceInput = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        balance = balanceInput.takeIf(String::isNotBlank)
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}
