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
import com.highcapable.kavaref.extension.createInstance
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelectionDialog
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "红包与支付/自动抢红包", description = "监听消息并自动拆开红包")
object AutoOpenRedPackets : ClickableHookItem(), WeDatabaseListenerApi.IInsertListener,
    IResolvesDex {

    private val TAG = nameOf(AutoOpenRedPackets)

    private val classReceiveLuckyMoney by dexClass()
    private val classOpenLuckyMoney by dexClass()
    private val methodReceiveOnGYNetEnd by dexMethod()
    private val methodOpenOnGYNetEnd by dexMethod()

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

        methodReceiveOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            val timingIdentifier = json.optString("timingIdentifier")

            if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap[sendId] ?: run {
                WeLogger.e(TAG, "failed to find red packet in map (sendId=$sendId)")
                return@hookAfter
            }
            WeLogger.i(
                TAG,
                "unpack request finished, sending open request ($sendId)"
            )

            thread(name = "OpenRedPacketThread") {
                try {
                    val openReq = classOpenLuckyMoney.clazz.createInstance(
                        info.msgType, info.channelId, info.sendId, info.nativeUrl,
                        info.headImg, info.nickName, info.talker,
                        "v1.0", timingIdentifier, ""
                    )
                    WeNetSceneApi.addNetSceneToQueue(openReq) // seems like this simple version works too
                    // we don't remove packet from map here for use in hookOpenReqEndCallback
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send open request", e)
                    currentRedPacketMap.remove(sendId)
                }
            }
        }

        methodOpenOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            if (sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap.remove(sendId) ?: return@hookAfter

            val retCode = json.optInt("retcode", -1)
            if (retCode != 0) {
                WeLogger.w(TAG, "failed to grab packet (retcode=$retCode, sendId=$sendId)")
                return@hookAfter
            }

            val amount = json.optInt("recAmount", 0)
            if (amount <= 0) return@hookAfter

            val displayAmount = amount / 100.0

            val autoReply = WePrefs.getStringOrDef("red_packet_auto_reply", "")
            if (autoReply.isNotBlank()) {
                WeMessageApi.sendText(info.talker, autoReply.replace($$"$amount", "¥$displayAmount"))
            }

            if (!WePrefs.getBoolOrFalse("red_packet_notification")) return@hookAfter

            val displayName = WeDatabaseApi.getDisplayName(info.talker)
            val isGroup = info.talker.endsWith("@chatroom")
            val sourceLabel = if (isGroup) "群组" else "私聊"
            showToast("抢到${sourceLabel}中「${displayName}」的红包 ¥${displayAmount}")
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
            if (values.getAsInteger("isSend") == 1 && !WePrefs.getBoolOrFalse("red_packet_self")) return

            val talker = values.getAsString("talker") ?: ""

            val useWhitelist = WePrefs.getBoolOrFalse("red_packet_use_whitelist")
            if (useWhitelist) {
                val whitelist = WePrefs.getStringSetOrDef("red_packet_whitelist", emptySet())
                if (talker !in whitelist) return
            } else {
                val blacklist = WePrefs.getStringSetOrDef("red_packet_blacklist", emptySet())
                if (talker in blacklist) return
            }

            val content = values.getAsString("content") ?: return

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

            val customDelay =
                WePrefs.getStringOrDef("red_packet_delay_custom", "0").toLongOrNull() ?: 0L
            val randomRange = (WePrefs.getStringOrDef("red_packet_delay_random_range", "300")
                .toLongOrNull() ?: 300L).coerceAtLeast(0)

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

            thread(name = "ReceiveRedPacketThread") {
                try {
                    if (delayTime > 0) {
                        WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                        Thread.sleep(delayTime)
                    }

                    WeLogger.i(
                        TAG,
                        "delay ended, preparing to send receive request (sendId=$sendId)"
                    )

                    val req = classReceiveLuckyMoney.clazz.createInstance(
                        msgType, channelId, sendId, nativeUrl, 1 /* inWay */, "v1.0" /* ver */, talker
                    )

                    WeNetSceneApi.addNetSceneToQueue(req) // seems like this simple version works too
                    WeLogger.i(TAG, "sent receive request (sendId=$sendId)")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send receive request (sendId=$sendId)", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
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
        WeDatabaseListenerApi.removeListener(this)
        currentRedPacketMap.clear()
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var notification by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_notification")) }
            var self by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_self")) }
            var delayInput by remember { mutableStateOf(WePrefs.getStringOrDef("red_packet_delay_custom", "500")) }
            var useWhitelist by remember { mutableStateOf(WePrefs.getBoolOrFalse("red_packet_use_whitelist")) }
            var randomRangeInput by remember { mutableStateOf(WePrefs.getStringOrDef("red_packet_delay_random_range", "300")) }
            var autoReplyInput by remember { mutableStateOf(WePrefs.getStringOrDef("red_packet_auto_reply", "")) }

            AlertDialogContent(
                title = { Text("自动抢红包") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text(if (useWhitelist) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                            supportingContent = { Text(if (useWhitelist) "仅对选中联系人抢红包" else "对选中联系人跳过抢红包") },
                            trailingContent = { Switch(checked = useWhitelist, onCheckedChange = { useWhitelist = it }) },
                            modifier = Modifier.clickable { useWhitelist = !useWhitelist }
                        )
                        ListItem(
                            headlineContent = { Text(if (useWhitelist) "配置白名单" else "配置黑名单") },
                            supportingContent = { Text("点击选择联系人") },
                            modifier = Modifier.clickable {
                                val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val listKey = if (useWhitelist) "red_packet_whitelist" else "red_packet_blacklist"
                                val currentList = WePrefs.getStringSetOrDef(listKey, emptySet())

                                showComposeDialog(context) {
                                    ContactsSelectionDialog(
                                        title = if (useWhitelist) "选择白名单" else "选择黑名单",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) {
                                        WePrefs.putStringSet(listKey, it)
                                        showToast("已保存 ${it.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            }
                        )
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
                        // TODO: refactor this to use js scripting
                        TextField(
                            value = autoReplyInput,
                            onValueChange = { autoReplyInput = it.trim() },
                            label = { Text("抢到后自动回复") },
                            supportingText = { Text($$"成功抢到红包后向来源对话发送自定义消息, 留空禁用\n(使用占位符 $amount 表示金额)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putBool("red_packet_notification", notification)
                        WePrefs.putBool("red_packet_self", self)
                        WePrefs.putString("red_packet_delay_custom", delayInput.ifBlank { "300" })
                        WePrefs.putBool("red_packet_use_whitelist", useWhitelist)
                        WePrefs.putString("red_packet_delay_random_range", randomRangeInput.ifBlank { "300" })
                        WePrefs.putString("red_packet_auto_reply", autoReplyInput)
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classReceiveLuckyMoney.find(dexKit) {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                    }
                }
            }
        }

        classOpenLuckyMoney.find(dexKit) {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        usingEqStrings("MicroMsg.NetSceneOpenLuckyMoney")
                    }
                }
            }
        }

        methodOpenOnGYNetEnd.find(dexKit) {
            matcher {
                declaredClass = classOpenLuckyMoney.getDescriptorString()!!
                name = "onGYNetEnd"
                paramCount = 3
            }
        }

        methodReceiveOnGYNetEnd.find(dexKit) {
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
