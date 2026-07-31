package dev.ujhhgtg.wekit.features.items.payment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@SuppressLint("DiscouragedApi")
@Feature(name = "自动抢红包", categories = ["红包与支付"], description = "监听消息并自动拆开红包")
object AutoOpenRedPackets : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    IResolveDex {

    private const val TAG = "AutoOpenRedPackets"

    /** 企业微信/OpenIM 群红包使用 union 领取链路, 对应源码中的 sceneid == 1005 */
    private const val UNION_SCENE_ID = 1005

    private val classReceiveLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                }
            }
        }
    }
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
    private val classOpenLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneOpenLuckyMoney")
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
    private val methodReceiveOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodReceiveUnionOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoneyUnion.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodOpenOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodOpenUnionOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoneyUnion.clazz)
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

        methodReceiveOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }
        methodReceiveUnionOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }

        methodOpenOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
        }
        methodOpenUnionOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
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
            if (!settings.matchesKeyword(nickName)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: keyword did not match")
                return
            }

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                sceneId = sceneId,
                headImg = headImg,
                nickName = nickName,
                notificationEnabled = settings.notification.enabled,
                autoReply = settings.autoReply.text.takeIf { settings.autoReply.enabled }.orEmpty()
            )

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

                    val req = if (sceneId == UNION_SCENE_ID) {
                        WeLogger.i(TAG, "using union receive request (sendId=$sendId)")
                        // 与宿主 LuckyMoneyNewReceiveUI 一致: union 领取请求固定 msgType=1 且不携带群名
                        classReceiveLuckyMoneyUnion.clazz.createInstance(
                            1, channelId, sendId, nativeUrl, 1 /* inWay */, "v1.0" /* ver */
                        )
                    } else {
                        classReceiveLuckyMoney.clazz.createInstance(
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

        thread(name = "OpenRedPacketThread") {
            try {
                val openReq = if (info.sceneId == UNION_SCENE_ID) {
                    WeLogger.i(TAG, "using union open request (sendId=$sendId)")
                    createUnionOpenRequest(info, timingIdentifier)
                } else {
                    classOpenLuckyMoney.clazz.createInstance(
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
        val sourceLabel = if (isGroup) "群组" else "私聊"
        showToast("抢到${sourceLabel}「${displayName}」中的红包 ¥${displayAmount}")
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
