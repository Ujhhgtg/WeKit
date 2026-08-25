package dev.ujhhgtg.wekit.features.items.payment

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

object OpenHistoryRedPackets : ClickableFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "捡漏历史红包"
    override val nameRes = R.string.feature_open_history_red_packets_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_open_history_red_packets_description

    private const val TAG = "OpenHistoryRedPackets"
    private const val PREF_KEY = "open_history_red_packets"

    // 微信红包超过 24 小时即过期, 扫描更早的消息没有意义
    private const val RED_PACKET_EXPIRY_MILLIS = 24L * 60 * 60 * 1000

    private var isRunning by mutableStateOf(false)
    private val logList = mutableStateListOf<HistoryLog>()

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null

    private data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val headImg: String = "",
        val nickName: String = ""
    )

    private data class HistoryLog(
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    )

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)

        WePaymentApi.methodReceiveLuckyMoneyOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            val timingIdentifier = json.optString("timingIdentifier")

            if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap[sendId] ?: return@hookAfter

            thread(name = "OpenHistoryRedPacketThread") {
                try {
                    val openReq = WePaymentApi.classOpenLuckyMoney.clazz.createInstance(
                        info.msgType, info.channelId, info.sendId, info.nativeUrl,
                        info.headImg, info.nickName, info.talker,
                        "v1.0", timingIdentifier, ""
                    )
                    WeNetSceneApi.sendNetScene(openReq)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to open request", e)
                    currentRedPacketMap.remove(sendId)
                    updateLog(R.string.payment_history_open_request_failed, info.nickName)
                }
            }
        }

        WePaymentApi.methodOpenLuckyMoneyOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter

            val sendId = json.optString("sendId")
            if (sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap.remove(sendId) ?: return@hookAfter
            val retCode = json.optInt("retcode", -1)

            if (retCode != 0) {
                updateLog(R.string.payment_history_receive_failed, info.nickName, retCode)
                return@hookAfter
            }

            when (val receiveStatus = json.optInt("receiveStatus", -1)) {
                2 -> {
                    val amount = json.optInt("amount", 0)
                    val displayAmount = amount / 100.0
                    updateLog(R.string.payment_history_receive_success, info.nickName, displayAmount)
                }

                3 -> updateLog(R.string.payment_history_expired, info.nickName)
                4 -> updateLog(R.string.payment_history_empty, info.nickName)
                else -> updateLog(R.string.payment_history_unknown_status, info.nickName, receiveStatus)
            }
        }
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
        stopScanning()
        currentRedPacketMap.clear()
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val convId = WeCurrentConversationApi.value
        if (convId.isEmpty()) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedPaymentString(R.string.feature_open_history_red_packets_name),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val convId = WeCurrentConversationApi.value
        if (convId.isEmpty()) return true

        if (!isRunning) {
            startScanning(convId)
            showProgressDialog(activity)
        } else {
            showToast(activity, activity.localizedPaymentString(R.string.payment_history_already_running))
            showProgressDialog(activity)
        }
        return true
    }

    override fun onClick(context: ComponentActivity) {
        if (isRunning) {
            showProgressDialog(context)
        } else {
            showToast(context, context.localizedPaymentString(R.string.payment_history_not_running))
        }
    }

    private fun startScanning(convId: String) {
        isRunning = true
        logList.clear()
        logList.add(HistoryLog(R.string.payment_history_scan_started))

        scanJob = scope.launch {
            try {
                var pageIndex = 1
                val pageSize = 20
                // 消息按 createTime 倒序返回, 一旦遇到超过 24 小时的消息, 其后的消息必然全部过期, 可直接停止扫描
                val expiryThreshold = System.currentTimeMillis() - RED_PACKET_EXPIRY_MILLIS
                var reachedExpired = false

                while (isRunning && !reachedExpired) {
                    val messages = WeDatabaseApi.getMessages(convId, pageIndex, pageSize)
                    if (messages.isEmpty()) {
                        break
                    }

                    for (msg in messages) {
                        if (!isRunning) break

                        if (msg.createTime < expiryThreshold) {
                            reachedExpired = true
                            break
                        }

                        val isRedPacket = MessageType.fromCode(msg.typeCode)?.isRedPacket == true
                        if (isRedPacket) {
                            parseAndReceiveRedPacket(msg)
                            delay(1500.milliseconds)
                        }
                    }
                    pageIndex++
                }

                if (isRunning) {
                    if (reachedExpired) {
                        updateLog(R.string.payment_history_scan_expired_complete)
                    } else {
                        updateLog(R.string.payment_history_scan_complete)
                    }
                    isRunning = false
                }
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    WeLogger.e(TAG, "扫描历史红包过程出错", e)
                    updateLog(
                        R.string.payment_history_scan_failed,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    )
                }
                isRunning = false
            }
        }
    }

    private fun parseAndReceiveRedPacket(msg: WeMessage) {
        try {
            val content = msg.content
            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return

            if (nickName.isEmpty()) {
                updateLog(R.string.payment_history_found_unnamed)
            } else {
                updateLog(R.string.payment_history_found, nickName)
            }

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = msg.talker,
                msgType = msgType,
                channelId = channelId,
                headImg = headImg,
                nickName = nickName
            )

            val req = WePaymentApi.classReceiveLuckyMoney.clazz.createInstance(
                msgType, channelId, sendId, nativeUrl, 1, "v1.0", msg.talker
            )
            WeNetSceneApi.sendNetScene(req)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse history red packet message", e)
        }
    }

    private fun stopScanning() {
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        updateLog(R.string.payment_history_stopped)
    }

    private fun updateLog(@StringRes messageRes: Int, vararg formatArgs: Any) {
        scope.launch(Dispatchers.Main) {
            logList.add(HistoryLog(messageRes, formatArgs.toList()))
        }
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val pattern = "<$tag><!\\[CDATA\\[(.*?)]]></$tag>".toRegex()
        val match = pattern.find(xml)
        if (match != null) return match.groupValues[1]
        val patternSimple = "<$tag>(.*?)</$tag>".toRegex()
        val matchSimple = patternSimple.find(xml)
        return matchSimple?.groupValues?.get(1) ?: ""
    }

    private fun showProgressDialog(context: Context) {
        showComposeDialog(context, directlyDismissable = false) {
            val listState = rememberLazyListState()

            LaunchedEffect(logList.size) {
                if (logList.isNotEmpty()) {
                    listState.animateScrollToItem(logList.size - 1)
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.payment_history_title)) },
                text = {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth(0.96f)
                            .fillMaxHeight(0.92f),
                    ) {
                        items(logList) { log ->
                            Text(
                                text = stringResource(log.messageRes, *log.formatArgs.toTypedArray()),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    if (isRunning) {
                        Button(onClick = {
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.payment_history_background))
                        }
                    }
                },
                dismissButton = {
                    if (isRunning) {
                        TextButton(onClick = {
                            stopScanning()
                        }) {
                            Text(stringResource(R.string.payment_history_stop))
                        }
                    } else {
                        TextButton(onClick = {
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.dialog_close))
                        }
                    }
                }
            )
        }
    }
}
