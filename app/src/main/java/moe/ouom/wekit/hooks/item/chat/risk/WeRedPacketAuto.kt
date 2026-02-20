package moe.ouom.wekit.hooks.item.chat.risk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.text.InputType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.net.toUri
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY_EXCLUSIVE
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseListener
import moe.ouom.wekit.hooks.sdk.api.WeNetworkApi
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.ui.creator.dialog.BaseRikkaDialogCompose
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天与消息/自动抢红包", desc = "监听消息并自动拆开红包")
class WeRedPacketAuto : BaseClickableFunctionHookItem(),
    WeDatabaseListener.DatabaseInsertListener, IDexFind {
    companion object {
        private const val TAG: String = "WeRedPacketAuto"
    }

    private val dexClsReceiveLuckyMoney by dexClass()
    private val dexClsOpenLuckyMoney by dexClass()
    private val dexMethodOnGYNetEnd by dexMethod()

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

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i(TAG, "entry() called, registering db listener")
        // 注册数据库监听
        WeDatabaseListener.addListener(this)
        WeLogger.i(TAG, "registered db listener")

        // Hook 具体的网络回调
        hookReceiveCallback()
        WeLogger.i(TAG, "hooked network receive callback")
    }

    /**
     * 接口实现：处理数据库插入事件
     */
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type == TYPE_LUCKY_MONEY || type == TYPE_LUCKY_MONEY_EXCLUSIVE) {
            WeLogger.i(TAG, "detected red packet message (type=$type)")
            handleRedPacket(values)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val config = WeConfig.getDefaultConfig()
            if (values.getAsInteger("isSend") == 1 && !config.getBoolPrek("red_packet_self")) return

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

            // 处理延时
            val isRandomDelay = config.getBoolPrek("red_packet_delay_random")
            val customDelay =
                config.getStringPrek("red_packet_delay_custom", "0")?.toLongOrNull() ?: 0L

            WeLogger.i(TAG, "read config - isRandomDelay=$isRandomDelay, customDelay=$customDelay")

            // 如果开启随机延迟，在自定义延迟基础上增加随机偏移
            val delayTime = if (isRandomDelay) {
                val baseDelay = if (customDelay > 0) customDelay else 1000L
                val randomOffset = Random.nextLong(-500, 500)
                val finalDelay = (baseDelay + randomOffset).coerceAtLeast(0)
                WeLogger.i(TAG, "random delay mode - baseDelay=$baseDelay, randomOffset=$randomOffset, finalDelay=$finalDelay")
                finalDelay
            } else {
                WeLogger.i(TAG, "fixed delay mode - delayTime=$customDelay")
                customDelay
            }

            Thread {
                try {
                    WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                    if (delayTime > 0) Thread.sleep(delayTime)

                    WeLogger.i(TAG, "delay ended, preparing to send open packet request (sendId=$sendId)")
                    val req = XposedHelpers.newInstance(
                        dexClsReceiveLuckyMoney.clazz,
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
        try {
            dexMethodOnGYNetEnd.toDexMethod {
                hook {
                    afterIfEnabled { param ->
                        val json = param.args[2] as? JSONObject ?: return@afterIfEnabled
                        val sendId = json.optString("sendId")
                        val timingIdentifier = json.optString("timingIdentifier")

                        if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@afterIfEnabled

                        val info = currentRedPacketMap[sendId] ?: return@afterIfEnabled
                        WeLogger.i(TAG, "unpack request finished, sending open packet request ($sendId)")

                        Thread {
                            try {
                                val openReq = XposedHelpers.newInstance(
                                    dexClsOpenLuckyMoney.clazz,
                                    info.msgType, info.channelId, info.sendId, info.nativeUrl,
                                    info.headImg, info.nickName, info.talker,
                                    "v1.0", timingIdentifier, ""
                                )
                                // 使用 NetworkApi 发送
                                WeNetworkApi.sendRequest(openReq)

                                currentRedPacketMap.remove(sendId)
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "failed to open packet", e)
                            }
                        }.start()
                    }
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to hook onGYNetEnd", e)
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

    override fun unload(classLoader: ClassLoader) {
        WeLogger.i(TAG, "unload() called, removing db listener")
        WeDatabaseListener.removeListener(this)
        currentRedPacketMap.clear()
        WeLogger.i(TAG, "removed db listener and cleared red packet map")
        super.unload(classLoader)
    }

    private class ConfigDialog(context: Context) : BaseRikkaDialogCompose(context, "自动抢红包") {

        override fun initPreferences() {
            addCategory("通用设置")

            addSwitchPreference(
                key = "red_packet_notification",
                title = "抢到后通知 (没写)",
                summary = "在通知栏显示抢到的金额"
            )

            addCategory("高级选项")

            addSwitchPreference(
                key = "red_packet_self",
                title = "抢自己的红包",
                summary = "默认情况下不抢自己发出的"
            )

            addEditTextPreference(
                key = "red_packet_delay_custom",
                title = "基础延迟",
                summary = "延迟时间",
                defaultValue = "1000",
                hint = "请输入延迟时间（毫秒）",
                inputType = InputType.TYPE_CLASS_NUMBER,
                maxLength = 5,
                summaryFormatter = { value ->
                    if (value.isEmpty()) "0 ms" else "$value ms"
                }
            )

            addSwitchPreference(
                key = "red_packet_delay_random",
                title = "随机延时",
                summary = "在基础延迟上增加 ±500ms 随机偏移，防止风控"
            )
        }
    }

    override fun onClick(context: Context?) {
        context?.let { ConfigDialog(it).show() }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找接收红包类
        dexClsReceiveLuckyMoney.find(dexKit, descriptors, allowMultiple = true) {
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
        val foundOpen = dexClsOpenLuckyMoney.find(dexKit, descriptors, allowMultiple = true) {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        usingStrings("MicroMsg.NetSceneOpenLuckyMoney")
                    }
                }
            }
        }
        if (!foundOpen) {
            WeLogger.e(TAG, "failed to find OpenLuckyMoney class")
            throw RuntimeException("DexKit: Failed to find OpenLuckyMoney class with string 'MicroMsg.NetSceneOpenLuckyMoney'")
        }

        // 查找 onGYNetEnd 回调方法
        val receiveLuckyMoneyClassName = dexClsReceiveLuckyMoney.getDescriptorString()
        if (receiveLuckyMoneyClassName != null) {
            val foundMethod = dexMethodOnGYNetEnd.find(dexKit, descriptors, true) {
                matcher {
                    declaredClass = receiveLuckyMoneyClassName
                    name = "onGYNetEnd"
                    paramCount = 3
                }
            }
            if (!foundMethod) {
                WeLogger.e(TAG, "failed to find onGYNetEnd method")
                throw RuntimeException("DexKit: Failed to find onGYNetEnd method in $receiveLuckyMoneyClassName")
            }
        }

        return descriptors
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context = context) { onDismiss ->
                AlertDialog(
                    onDismissRequest = { onDismiss() },
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        TextButton(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { onDismiss() }) {
                            Text("取消")
                        }
                    }
                )
            }
            // Return false to prevent the UI from toggling until "确定" is pressed
            return false
        }

        // Direct allow when disabling
        return true
    }
}