package dev.ujhhgtg.wekit.hooks.items.payment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.core.net.toUri
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.core.WeNetworkApi
import dev.ujhhgtg.wekit.hooks.api.core.model.MessageType
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.showToast
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "红包与支付/自动抢红包", desc = "监听消息并自动拆开红包")
object AutoOpenRedPackets : ClickableHookItem(), WeDatabaseListenerApi.IInsertListener,
    IResolvesDex {

    private val TAG = nameOf(AutoOpenRedPackets)

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
                val randomOffset = Random.nextLong(-300, 300)
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
        methodOnGYNetEnd.hookAfter { param ->
            val json = param.args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            val timingIdentifier = json.optString("timingIdentifier")

            if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap[sendId] ?: return@hookAfter
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

    private fun hookOpenReqEndCallback() {
        methodOnOpenGYNetEnd.hookAfter { param ->
            val notifEnabled = WePrefs.getBoolOrFalse("red_packet_notification")
            if (!notifEnabled) return@hookAfter

            val json = param.args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            if (sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap.remove(sendId) ?: return@hookAfter

            val retcode = json.optInt("retcode", -1)
            if (retcode != 0) {
                WeLogger.w(TAG, "failed to grab packet; retcode=$retcode, sendId=$sendId")
                return@hookAfter
            }

            val amount = json.optInt("recAmount", 0)
            if (amount <= 0) {
                return@hookAfter
            }

            val displayAmount = amount / 100.0
            val displayName = WeDatabaseApi.getDisplayName(info.talker)
            val isGroup = info.talker.endsWith("@chatroom")
            val sourceLabel = if (isGroup) "群组" else "私聊"
            showToast("抢到来自${sourceLabel}中来自 '${displayName}' 的红包 ¥${displayAmount}")
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

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var notification by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_notification")) }
            var self by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_self")) }
            var delayInput by remember { mutableStateOf(WePrefs.getStringOrDef("red_packet_delay_custom", "500")) }
            var useRandomDelay by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_delay_random")) }

            AlertDialogContent(
                title = { Text("自动抢红包") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("抢到后通知") },
                            supportingContent = { Text("使用 Toast 显示抢到的金额") },
                            trailingContent = { Switch(checked = notification, onCheckedChange = { notification = it }) },
                            modifier = Modifier.clickable { notification = !notification }
                        )
                        ListItem(
                            headlineContent = { Text("抢自己的红包") },
                            supportingContent = { Text("默认情况下不抢自己发出的红包") },
                            trailingContent = { Switch(checked = self, onCheckedChange = { self = it }) },
                            modifier = Modifier.clickable { self = !self }
                        )
                        TextField(
                            value = delayInput,
                            onValueChange = { delayInput = it.take(5) },
                            label = { Text("基础延迟 (毫秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        ListItem(
                            headlineContent = { Text("随机延时") },
                            supportingContent = { Text("在基础延迟上增加 ±300ms 随机偏移, 防止风控") },
                            trailingContent = { Switch(checked = useRandomDelay, onCheckedChange = { useRandomDelay = it }) },
                            modifier = Modifier.clickable { useRandomDelay = !useRandomDelay }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putBool("red_packet_notification", notification)
                        WePrefs.putBool("red_packet_self", self)
                        WePrefs.putBool("red_packet_delay_random", useRandomDelay)
                        WePrefs.putString("red_packet_delay_custom", delayInput.ifBlank { "500" })
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 查找接收红包类
        classReceiveLuckyMoney.find(dexKit, allowMultiple = true) {
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
        classOpenLuckyMoney.find(dexKit, allowMultiple = true) {
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
        methodOnGYNetEnd.find(dexKit, true) {
            matcher {
                declaredClass = classReceiveLuckyMoney.getDescriptorString()!!
                name = "onGYNetEnd"
                paramCount = 3
            }
        }

        methodOnOpenGYNetEnd.find(dexKit, true) {
            matcher {
                declaredClass = classReceiveLuckyMoney.getDescriptorString()!!
                name = "onGYNetEnd"
                paramCount = 3
            }
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
