package moe.ouom.wekit.hooks.items.payment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.text.InputType
import androidx.compose.material3.Text
import androidx.core.net.toUri
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.core.WeDatabaseListenerApi
import moe.ouom.wekit.hooks.api.core.WeNetworkApi
import moe.ouom.wekit.hooks.api.core.model.MessageType
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.BasePrefDialog
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "红包与支付/自动抢红包", desc = "监听消息并自动拆开红包")
object AutoOpenRedPacket : ClickableHookItem(), WeDatabaseListenerApi.IInsertListener,
    IResolvesDex {

    private val TAG = nameof(AutoOpenRedPacket)

    private val classReceiveLuckyMoney by dexClass()
    private val classOpenLuckyMoney by dexClass()
    private val methodOnGYNetEnd by dexMethod()
    private val methodOnOpenGYNetEnd by dexMethod()

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val headImg: String = "",
        val nickName: String = ""
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        hookReceiveCallback()
        hookOpenReqEndCallback()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (MessageType.isRedPacket(type)) {
            WeLogger.i(TAG, "detected red packet message; type=$type")
            handleRedPacket(values)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            if (values.getAsInteger("isSend") == 1 && !WePrefs.getBoolOrFalse("red_packet_self")) return

            val content = values.getAsString("content") ?: return
            val talker = values.getAsString("talker") ?: ""

            // 解析 XML 内容
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

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                headImg = headImg,
                nickName = nickName
            )

            val isRandomDelay = WePrefs.getBoolOrFalse("red_packet_delay_random")
            val customDelay =
                WePrefs.getStringOrDef("red_packet_delay_custom", "0").toLongOrNull() ?: 0L

            WeLogger.i(TAG, "config - isRandomDelay=$isRandomDelay, customDelay=$customDelay")

            val delayTime = if (isRandomDelay) {
                val baseDelay = if (customDelay > 0) customDelay else 1000L
                val randomOffset = Random.nextLong(-500, 500)
                val finalDelay = (baseDelay + randomOffset).coerceAtLeast(0)
                WeLogger.i(
                    TAG,
                    "random delay mode - baseDelay=$baseDelay, randomOffset=$randomOffset, finalDelay=$finalDelay"
                )
                finalDelay
            } else {
                WeLogger.i(TAG, "fixed delay mode - delayTime=$customDelay")
                customDelay
            }

            Thread {
                try {
                    WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                    if (delayTime > 0) Thread.sleep(delayTime)

                    WeLogger.i(
                        TAG,
                        "delay ended, preparing to send open packet request (sendId=$sendId)"
                    )
                    val req = XposedHelpers.newInstance(
                        classReceiveLuckyMoney.clazz,
                        msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker
                    )

                    WeNetworkApi.sendRequest(req)
                    WeLogger.i(TAG, "sent unpack packet request (sendId=$sendId)")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send unpack packet request (sendId=$sendId)", e)
                }
            }.start()

        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
        }
    }

    private fun hookReceiveCallback() {
        methodOnGYNetEnd.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val json = param.args[2] as? JSONObject ?: return@afterIfEnabled
                    val sendId = json.optString("sendId")
                    val timingIdentifier = json.optString("timingIdentifier")

                    if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@afterIfEnabled

                    val info = currentRedPacketMap[sendId] ?: return@afterIfEnabled
                    WeLogger.i(
                        TAG,
                        "unpack request finished, sending open packet request ($sendId)"
                    )

                    Thread {
                        try {
                            val openReq = XposedHelpers.newInstance(
                                classOpenLuckyMoney.clazz,
                                info.msgType, info.channelId, info.sendId, info.nativeUrl,
                                info.headImg, info.nickName, info.talker,
                                "v1.0", timingIdentifier, ""
                            )
                            WeNetworkApi.sendRequest(openReq)
                            // we don't remove packet from map here for use in hookOpenReqEndCallback
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "failed to open packet", e)
                            currentRedPacketMap.remove(sendId)
                        }
                    }.start()
                }
            }
        }
    }

    private fun hookOpenReqEndCallback() {
        methodOnOpenGYNetEnd.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val notifEnabled = WePrefs.getBoolOrFalse("red_packet_notification")
                    if (!notifEnabled) return@afterIfEnabled

                    val json = param.args[2] as? JSONObject ?: return@afterIfEnabled
                    val sendId = json.optString("sendId")
                    if (sendId.isNullOrEmpty()) return@afterIfEnabled

                    val info = currentRedPacketMap.remove(sendId) ?: return@afterIfEnabled

                    val retcode = json.optInt("retcode", -1)
                    if (retcode != 0) {
                        WeLogger.w(TAG, "failed to grab packet; retcode=$retcode, sendId=$sendId")
                        return@afterIfEnabled
                    }

                    val amount = json.optInt("recAmount", 0)
                    if (amount <= 0) {
                        return@afterIfEnabled
                    }

                    val displayAmount = amount / 100.0
                    val displayName = WeDatabaseApi.getDisplayName(info.talker)
                    val isGroup = info.talker.endsWith("@chatroom")
                    val sourceLabel = if (isGroup) "群组" else "私聊"
                    ToastUtils.showToast("抢到来自${sourceLabel}中来自 '${displayName}' 的红包 ¥${displayAmount}")
                }
            }
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

    override fun onDisable() {
        WeLogger.i(TAG, "unload() called, removing db listener")
        WeDatabaseListenerApi.removeListener(this)
        currentRedPacketMap.clear()
        WeLogger.i(TAG, "removed db listener and cleared red packet map")
    }

    private class ConfigDialog(context: Context) : BasePrefDialog(context, "自动抢红包") {

        override fun initPreferences() {
            addSwitchPreference(
                key = "red_packet_notification",
                title = "抢到后通知",
                summary = "使用 Toast 显示抢到的金额"
            )

            addSwitchPreference(
                key = "red_packet_self",
                title = "抢自己的红包",
                summary = "默认情况下不抢自己发出的红包"
            )

            addEditTextPreference(
                key = "red_packet_delay_custom",
                title = "基础延迟",
                summary = "延迟时间",
                defaultValue = "500",
                hint = "请输入延迟时间 (毫秒)",
                inputType = InputType.TYPE_CLASS_NUMBER,
                maxLength = 5,
                summaryFormatter = { value ->
                    if (value.isEmpty()) "0 ms" else "$value ms"
                }
            )

            addSwitchPreference(
                key = "red_packet_delay_random",
                title = "随机延时",
                summary = "在基础延迟上增加 ±500ms 随机偏移, 防止风控"
            )
        }
    }

    override fun onClick(context: Context) {
        ConfigDialog(context).show()
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找接收红包类
        classReceiveLuckyMoney.find(dexKit, descriptors, allowMultiple = true) {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        usingStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                    }
                }
            }
        }

        // 查找开红包类
        classOpenLuckyMoney.find(dexKit, descriptors, allowMultiple = true) {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        usingStrings("MicroMsg.NetSceneOpenLuckyMoney")
                    }
                }
            }
        }

        // 查找 onGYNetEnd 回调方法
        methodOnGYNetEnd.find(dexKit, descriptors, true) {
            matcher {
                declaredClass = classReceiveLuckyMoney.getDescriptorString()!!
                name = "onGYNetEnd"
                paramCount = 3
            }
        }

        methodOnOpenGYNetEnd.find(dexKit, descriptors, true) {
            matcher {
                declaredClass = classReceiveLuckyMoney.getDescriptorString()!!
                name = "onGYNetEnd"
                paramCount = 3
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
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
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