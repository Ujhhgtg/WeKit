package dev.ujhhgtg.wekit.features.items.payment

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

object AutoAcceptTransfers : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动接收转账"
    override val nameRes = R.string.feature_auto_accept_transfers_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_auto_accept_transfers_description

    private const val TAG = "AutoAcceptTransfers"

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type != MessageType.TRANSFER.code) return

        WeLogger.i(TAG, "detected transfer message; type=$type")
        handleTransfer(values)
    }

    private val PAY_SUBTYPE_REGEX = Regex("<paysubtype.*?(\\d+)</paysubtype>")

    private fun parsePaySubtypeFromXml(xml: String): String? {
        return runCatching {
            PAY_SUBTYPE_REGEX
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }.getOrDefault(null)
    }

    private fun handleTransfer(values: ContentValues) {
        val talker = values.getAsString("talker") ?: ""
        val content = values.getAsString("content") ?: return

        val subtype = parsePaySubtypeFromXml(content)
        if (subtype != "1") {
            WeLogger.w(TAG, "status=$subtype is not 1, ignoring")
            return
        }

        val msgInfo = MessageInfo.fromContentValues(values)

        val transferMsg = msgInfo.toTransferMessage() ?: run {
            WeLogger.w(TAG, "failed to parse transfer message")
            return
        }

        val payerUsername = transferMsg.payerUsername.ifEmpty { msgInfo.sender }.ifEmpty { msgInfo.talker }

        if (payerUsername == WeApi.selfWxId) {
            WeLogger.w(TAG, "self is payer, ignoring")
            return
        }

        val receiverUsername = transferMsg.receiverUsername
        if (receiverUsername != WeApi.selfWxId) {
            WeLogger.w(TAG, "self is not receiver, ignoring")
            return
        }

        val rules = TransferSettings.resolve(talker, payerUsername)
        val totalFeeCents = transferMsg.totalFee.takeIf { it > 0L }
            ?: parseFeedescCents(transferMsg.feedesc)
        if (rules.amountRange.enabled && totalFeeCents == null) {
            WeLogger.w(TAG, "amount rule is enabled but transfer amount cannot be parsed; feedesc=${transferMsg.feedesc}")
            return
        }
        if (!rules.accepts(totalFeeCents ?: 0L, transferMsg.payMemo)) return

        val delayTime = rules.delay.millis()
        WeLogger.i(
            TAG,
            "matched transfer rules: talker=$talker, payer=$payerUsername, totalFee=$totalFeeCents, " +
                    "feeType=${transferMsg.feeType}, delay=$delayTime"
        )

        thread(name = "AcceptTransferThread") {
            try {
                if (delayTime > 0) {
                    WeLogger.i(TAG, "started delaying for ${delayTime}ms")
                    Thread.sleep(delayTime)
                }

                WePaymentApi.confirmTransfer(transferMsg.transactionId, transferMsg.transferId, payerUsername, transferMsg.invalidTime)
                WeLogger.i(TAG, "called WePaymentApi.confirmTransfer")

                if (rules.autoReply.enabled) {
                    WeMessageApi.sendText(
                        msgInfo.talker,
                        rules.autoReply.text.replace($$"$amount", transferMsg.feedesc)
                    )
                }

                if (!rules.notification.enabled) return@thread

                val displayName = WeDatabaseApi.getDisplayName(payerUsername)

                runOnUiThread {
                    showToast(
                        localizedPaymentString(
                            R.string.payment_transfer_received,
                            displayName,
                            transferMsg.feedesc,
                        )
                    )
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "failed to send accept transfer request", e)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        TransferSettings.showMainDialog(context)
    }

    private fun parseFeedescCents(feedesc: String): Long? {
        val amount = Regex("\\d+(?:\\.\\d{1,2})?").find(feedesc)?.value ?: return null
        return runCatching {
            BigDecimal(amount)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = stringResource(R.string.warning)) },
                    text = { Text(text = stringResource(R.string.payment_risk_warning)) },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text(stringResource(R.string.dialog_confirm)) }
                    },
                    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
                )
            }
            return false
        }

        return true
    }
}
