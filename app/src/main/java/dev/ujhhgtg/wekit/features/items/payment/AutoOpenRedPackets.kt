package dev.ujhhgtg.wekit.features.items.payment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.items.payment.RedPacketSettings.ReceiveMode
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@SuppressLint("DiscouragedApi")
object AutoOpenRedPackets : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    IResolveDex {

    override val technicalId = "自动抢红包"
    override val nameRes = R.string.feature_auto_open_red_packets_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_auto_open_red_packets_description

    private const val TAG = "AutoOpenRedPackets"

    /** 企业微信/OpenIM 群红包使用 union 领取链路, 对应源码中的 sceneid == 1005 */
    private const val UNION_SCENE_ID = 1005
    private const val EXTRA_CLICK_RECEIVE = "Nuke.AutoReceiveRedPacket.ClickReceive"
    private const val EXTRA_CLICK_RECEIVE_SCHEDULED = "Nuke.AutoReceiveRedPacket.ClickReceiveScheduled"

    /** 与宿主 LuckyMoneyNotHookReceiveUI 兼容的领取页启动参数 (来自 Nuke 1.0.2 hh.m)。 */
    private val REPORT_KEY_COMMON_REPORT_OBJ = byteArrayOf(
        10, 109, 10, 76, 97, 117, 110, 99, 104, 101, 114, 85, 73, 18, 36,
        48, 54, 99, 48, 55, 49, 101, 56, 45, 55, 52, 50, 98, 45, 52, 48, 97, 99, 45, 97, 100, 54, 48, 45, 57, 57, 101, 51, 102, 55, 100, 98, 99, 50, 55, 49,
        24, -57, -89, 50, 32, 0, 40, 1, 50, 46, 8, 9, 18, 42, 10, 20,
        49, 49, 53, 56, 51, 50, 56, 48, 50, 54, 57, 52, 51, 53, 57, 56, 49, 56, 52, 54,
        40, 0, 48, 0, 56, 0, 64, 0, 72, 0, 80, 0, 88, 0, 96, 0, 104, 0, 112, 0, 120, 0,
    )

    private val classReceiveLuckyMoneyUnion by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoneyUnion")
                }
            }
        }
    }
    private val classOpenLuckyMoneyUnion by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneOpenLuckyMoneyUnion")
                }
            }
        }
    }
    private val classLuckyMoneyNotHookReceiveUI by dexClass {
        matcher {
            usingEqStrings("LuckyMoneyNotHookReceiveUI")
        }
    }
    private val methodReceiveUnionOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoneyUnion.data.name)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodOpenUnionOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoneyUnion.data.name)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    private data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val sceneId: Int = 0,
        val receiveMode: ReceiveMode = ReceiveMode.NETWORK,
        val headImg: String = "",
        val nickName: String = "",
        val notificationEnabled: Boolean = false,
        val autoReply: String = ""
    )

    /** 8.0.74+ 的 union 打开请求多了 left_button_continue 参数 */
    private val unionOpenHasLeftButtonContinue: Boolean by lazy {
        classOpenLuckyMoneyUnion.clazz.declaredConstructors.any { it.parameterCount == 10 }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        WePaymentApi.methodReceiveLuckyMoneyOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }
        methodReceiveUnionOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }

        WePaymentApi.methodOpenLuckyMoneyOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
        }
        methodOpenUnionOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
        }

        classLuckyMoneyNotHookReceiveUI.clazz.declaredMethods
            .filter { it.name == "onSceneEnd" }
            .forEach { method ->
                method.hookAfter(50) {
                    val activity = thisObject as Activity
                    val intent = activity.intent
                    if (intent.getBooleanExtra(EXTRA_CLICK_RECEIVE, false) &&
                        !intent.getBooleanExtra(EXTRA_CLICK_RECEIVE_SCHEDULED, false)
                    ) {
                        intent.putExtra(EXTRA_CLICK_RECEIVE_SCHEDULED, true)
                        val button = findFirstButton(activity.window.decorView)
                        if (button != null) {
                            WeLogger.i(TAG, "auto-clicking receive button in host UI")
                            button.performClick()
                        }
                    }
                }
            }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (MessageType.fromCode(type)?.isRedPacket ?: false) {
            WeLogger.i(TAG, "detected red packet message; type=$type")
            handleRedPacket(values)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val msgInfo = MessageInfo.fromContentValues(values)
            val talker = msgInfo.talker
            val content = msgInfo.content
            val isGroupChat = msgInfo.isInGroupChat
            val sender = msgInfo.sender
            val settings = RedPacketSettings.resolve(talker, sender.takeIf { isGroupChat })

            if (!settings.grab.enabled) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: grabbing disabled")
                return
            }
            if (msgInfo.isSelfSender && !settings.grabSelf.enabled) {
                WeLogger.i(TAG, "skipping self-sent packet in $talker")
                return
            }
            if (!settings.isInActiveTime()) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: outside active time range")
                return
            }

            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sceneId = extractXmlParam(xmlContent, "sceneid").toIntOrNull() ?: 0
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return
            if (settings.skipKeyword.enabled && settings.skipKeyword.matches(nickName)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: skip keyword matched")
                return
            }
            if (!settings.matchesKeyword(nickName)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: keyword did not match")
                return
            }

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            val info = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                sceneId = sceneId,
                receiveMode = settings.receiveMode,
                headImg = headImg,
                nickName = nickName,
                notificationEnabled = settings.notification.enabled,
                autoReply = settings.autoReply.text.takeIf { settings.autoReply.enabled }.orEmpty()
            )
            currentRedPacketMap[sendId] = info

            val delayTime = settings.delayMillis()
            WeLogger.i(TAG, "resolved delay: ${delayTime}ms (sendId=$sendId)")

            thread(name = "ReceiveRedPacketThread") {
                try {
                    if (delayTime > 0) {
                        WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                        Thread.sleep(delayTime)
                    }

                    WeLogger.i(
                        TAG,
                        "delay ended, preparing to send receive request (sendId=$sendId, sceneId=$sceneId)"
                    )

                    if (info.receiveMode == ReceiveMode.CLICK) {
                        WeLogger.i(TAG, "click receive mode: launching host receive UI (sendId=$sendId)")
                        launchClickReceiveUi(info)
                        return@thread
                    }

                    val req = if (sceneId == UNION_SCENE_ID) {
                        WeLogger.i(TAG, "using union receive request (sendId=$sendId)")
                        // 与宿主 LuckyMoneyNewReceiveUI 一致: union 领取请求固定 msgType=1 且不携带群名
                        classReceiveLuckyMoneyUnion.clazz.createInstance(
                            1, channelId, sendId, nativeUrl, 1 /* inWay */, "v1.0" /* ver */
                        )
                    } else {
                        WePaymentApi.classReceiveLuckyMoney.clazz.createInstance(
                            msgType, channelId, sendId, nativeUrl, 1 /* inWay */, "v1.0" /* ver */, talker
                        )
                    }

                    WeNetSceneApi.sendNetScene(req)
                    WeLogger.i(TAG, "sent receive request (sendId=$sendId)")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send receive request (sendId=$sendId)", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
        }
    }

    private fun launchClickReceiveUi(info: RedPacketInfo) {
        val activity = getTopMostActivity()
        if (activity == null) {
            WeLogger.w(TAG, "no foreground activity for click receive (sendId=${info.sendId})")
            currentRedPacketMap.remove(info.sendId)
            return
        }
        val intent = Intent(activity, classLuckyMoneyNotHookReceiveUI.clazz).apply {
            putExtra(EXTRA_CLICK_RECEIVE, true)
            putExtra("KEY_HOME_PAGE_CLS", "com.tencent.mm.ui.LauncherUI")
            putExtra("key_username", info.talker)
            putExtra("key_way", 1)
            putExtra("key_native_url", info.nativeUrl)
            putExtra("ReportKey.CommonReportObjKey", REPORT_KEY_COMMON_REPORT_OBJ)
            putExtra("key_cropname", "")
        }
        activity.runOnUiThread {
            try {
                activity.startActivity(intent)
                WeLogger.i(TAG, "opened red packet receive UI (sendId=${info.sendId})")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "open red packet receive UI failed (sendId=${info.sendId})", e)
                currentRedPacketMap.remove(info.sendId)
            }
        }
    }

    private fun findFirstButton(container: View): android.widget.Button? {
        if (container is android.widget.Button) return container
        if (container !is ViewGroup) return null
        for (i in 0 until container.childCount) {
            findFirstButton(container.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun handleReceiveResponse(json: JSONObject?) {
        json ?: return

        val sendId = json.optString("sendId")
        val timingIdentifier = json.optString("timingIdentifier")

        if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return

        val info = currentRedPacketMap[sendId] ?: run {
            WeLogger.e(TAG, "failed to find red packet in map (sendId=$sendId)")
            return
        }
        WeLogger.i(
            TAG,
            "unpack request finished, sending open request (sendId=$sendId, sceneId=${info.sceneId})"
        )

        if (info.receiveMode == ReceiveMode.CLICK) {
            WeLogger.i(TAG, "click receive mode: host UI handles the open request (sendId=$sendId)")
            return
        }

        thread(name = "OpenRedPacketThread") {
            try {
                val openReq = if (info.sceneId == UNION_SCENE_ID) {
                    WeLogger.i(TAG, "using union open request (sendId=$sendId)")
                    createUnionOpenRequest(info, timingIdentifier)
                } else {
                    WePaymentApi.classOpenLuckyMoney.clazz.createInstance(
                        info.msgType, info.channelId, info.sendId, info.nativeUrl,
                        info.headImg, info.nickName, info.talker,
                        "v1.0", timingIdentifier, ""
                    )
                }
                WeNetSceneApi.sendNetScene(openReq)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "failed to send open request", e)
                currentRedPacketMap.remove(sendId)
            }
        }
    }

    private fun handleOpenResponse(json: JSONObject?) {
        json ?: return

        val sendId = json.optString("sendId")
        if (sendId.isNullOrEmpty()) return

        val info = currentRedPacketMap.remove(sendId) ?: return

        val retCode = json.optInt("retcode", -1)
        if (retCode != 0) {
            WeLogger.w(TAG, "failed to grab packet (retcode=$retCode, sendId=$sendId)")
            return
        }

        val receiveStatus = json.optInt("receiveStatus", -1)
        if (receiveStatus != 2) {
            WeLogger.w(TAG, "missed the packet (recvStatus=$receiveStatus, sendId=$sendId)")
            return
        }

        val amount = json.optInt("amount", 0)
        if (amount <= 0) return

        val displayAmount = amount / 100.0

        val reply = info.autoReply
        if (reply.isNotBlank()) {
            WeMessageApi.sendText(info.talker, reply.replace($$"$amount", "¥$displayAmount"))
        }

        if (!info.notificationEnabled) return

        val displayName = WeDatabaseApi.getDisplayName(info.talker)
        val isGroup = info.talker.isGroupChatWxId
        val sourceLabel = localizedPaymentString(
            if (isGroup) R.string.payment_source_group else R.string.payment_source_private_chat
        )
        showToast(
            localizedPaymentString(
                R.string.payment_red_packet_received,
                sourceLabel,
                displayName,
                displayAmount,
            )
        )
    }

    private fun createUnionOpenRequest(info: RedPacketInfo, timingIdentifier: String): Any {
        val args = mutableListOf<Any?>(
            1, info.channelId, info.sendId, info.nativeUrl,
            info.headImg, info.nickName, info.talker,
            "v1.0", timingIdentifier
        )
        // 8.0.74+ 的 NetSceneOpenLuckyMoneyUnion 构造函数带 left_button_continue
        if (unionOpenHasLeftButtonContinue) args += ""
        return classOpenLuckyMoneyUnion.clazz.createInstance(*args.toTypedArray())
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val pattern = "<$tag><!\\[CDATA\\[(.*?)]]></$tag>".toRegex()
        val match = pattern.find(xml)
        if (match != null) return match.groupValues[1]
        val patternSimple = "<$tag>(.*?)</$tag>".toRegex()
        val matchSimple = patternSimple.find(xml)
        return matchSimple?.groupValues?.get(1) ?: ""
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        currentRedPacketMap.clear()
    }

    override fun onClick(context: ComponentActivity) {
        RedPacketSettings.showMainDialog(context)
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
