package dev.ujhhgtg.wekit.features.items.payment

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.concurrent.thread
import kotlin.random.Random

@Feature(name = "自动接收转账", categories = ["红包与支付"], description = "监听消息并自动接收转账")
object AutoAcceptTransfers : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoAcceptTransfers"

    private var transferNotif by WePrefs.prefOption("transfer_notification", false)
    private var transferUseWhitelist by WePrefs.prefOption("transfer_use_whitelist", false)
    private var transferWhitelist by WePrefs.prefOption("transfer_whitelist", emptySet())
    private var transferBlacklist by WePrefs.prefOption("transfer_blacklist", emptySet())
    private var transferDelayCustom by WePrefs.prefOption("transfer_delay_custom", "500")
    private var transferDelayRandomRange by WePrefs.prefOption("transfer_delay_random_range", "300")
    private var transferAutoReply by WePrefs.prefOption("transfer_auto_reply", "")

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

        if (transferUseWhitelist) {
            if (talker !in transferWhitelist) return
        } else {
            if (talker in transferBlacklist) return
        }

        val content = values.getAsString("content") ?: return

        val subtype = parsePaySubtypeFromXml(content)
        if (subtype != "1") {
            WeLogger.w(TAG, "status=$subtype is not 1, ignoring")
            return
        }

        val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoInstanceFromContentValues(values))

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

        val customDelay = transferDelayCustom.toLongOrNull() ?: 0L
        val randomRange = (transferDelayRandomRange.toLongOrNull() ?: 300L).coerceAtLeast(0)

        WeLogger.i(TAG, "config: customDelay=$customDelay, randomRange=$randomRange")

        val delayTime = if (randomRange > 0) {
            val baseDelay = if (customDelay > 0) customDelay else 1000L
            val randomOffset = Random.nextLong(-randomRange, randomRange)
            val finalDelay = (baseDelay + randomOffset).coerceAtLeast(0)
            WeLogger.i(
                TAG,
                "random delay mode: baseDelay=$baseDelay, randomOffset=$randomOffset, finalDelay=$finalDelay"
            )
            finalDelay
        } else {
            WeLogger.i(TAG, "fixed delay mode: finalDelay=$customDelay")
            customDelay
        }

        thread(name = "AcceptTransferThread") {
            try {
                if (delayTime > 0) {
                    WeLogger.i(TAG, "started delaying for ${delayTime}ms")
                    Thread.sleep(delayTime)
                }

                WePaymentApi.confirmTransfer(transferMsg.transactionId, transferMsg.transferId, payerUsername, transferMsg.invalidTime)
                WeLogger.i(TAG, "called WePaymentApi.confirmTransfer")

                val autoReply = transferAutoReply
                if (autoReply.isNotBlank()) {
                    WeMessageApi.sendText(msgInfo.talker, autoReply.replace($$"$amount", transferMsg.feedesc))
                }

                if (!transferNotif) return@thread

                val displayName = WeDatabaseApi.getDisplayName(payerUsername)

                Handler(Looper.getMainLooper()).post {
                    showToast("收到「${displayName}」的转账 ${transferMsg.feedesc}")
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "failed to send accept transfer request", e)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var notification by remember { mutableStateOf(transferNotif) }
            var delayInput by remember { mutableStateOf(transferDelayCustom) }
            var useWhitelist by remember { mutableStateOf(transferUseWhitelist) }
            var randomRangeInput by remember { mutableStateOf(transferDelayRandomRange) }
            var autoReplyInput by remember { mutableStateOf(transferAutoReply) }

            AlertDialogContent(
                title = { Text("自动接收转账") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            headlineContent = { Text(if (useWhitelist) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                            supportingContent = { Text(if (useWhitelist) "仅对选中联系人接收转账" else "对选中联系人跳过接收转账") },
                            trailingContent = { Switch(checked = useWhitelist, onCheckedChange = { useWhitelist = it }) },
                            modifier = Modifier.clickable { useWhitelist = !useWhitelist }
                        )
                        ListItem(
                            headlineContent = { Text(if (useWhitelist) "配置白名单" else "配置黑名单") },
                            supportingContent = { Text("点击选择联系人") },
                            modifier = Modifier.clickable {
                                val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelist) transferWhitelist else transferBlacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = if (useWhitelist) "选择白名单" else "选择黑名单",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selected ->
                                        if (useWhitelist) {
                                            transferWhitelist = selected
                                        } else {
                                            transferBlacklist = selected
                                        }
                                        showToast("已保存 ${selected.size} 个联系人")
                                        onDismiss()
                                    }
                                }
                            }
                        )
                        ListItem(
                            headlineContent = { Text("接收后通知") },
                            supportingContent = { Text("使用 Toast 显示收到的金额") },
                            trailingContent = { Switch(checked = notification, onCheckedChange = { notification = it }) },
                            modifier = Modifier.clickable { notification = !notification }
                        )
                        TextField(
                            value = delayInput,
                            onValueChange = { delayInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("基础延迟 (毫秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = randomRangeInput,
                            onValueChange = { randomRangeInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("随机偏移范围 (±毫秒)") },
                            supportingText = { Text("在基础延迟上增加随机偏移, 防止风控, 设 0 固定使用基础延迟") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        TextField(
                            value = autoReplyInput,
                            onValueChange = { autoReplyInput = it.trim() },
                            label = { Text("接收后自动回复 (留空禁用)") },
                            supportingText = { Text($$"自动接收转账后向来源对话发送自定义消息\n(使用占位符 $amount 表示金额)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        transferNotif = notification
                        transferDelayCustom = delayInput.ifBlank { "500" }
                        transferUseWhitelist = useWhitelist
                        transferDelayRandomRange = randomRangeInput.ifBlank { "300" }
                        transferAutoReply = autoReplyInput
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
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
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } }
                )
            }
            return false
        }

        return true
    }
}
