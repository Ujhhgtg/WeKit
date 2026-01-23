package moe.ouom.wekit.hooks.item.chat.risk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.core.net.toUri
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY
import moe.ouom.wekit.constants.Constants.Companion.TYPE_LUCKY_MONEY_EXCLUSIVE
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseApi
import moe.ouom.wekit.hooks.sdk.api.WeNetworkApi
import moe.ouom.wekit.ui.creator.dialog.item.WeRedPacketConfigDialog
import moe.ouom.wekit.util.log.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天与消息/自动抢红包", desc = "监听消息并自动拆开红包")
class WeRedPacketAuto : BaseClickableFunctionHookItem(), WeDatabaseApi.DatabaseInsertListener, IDexFind {

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
        // 注册数据库监听
        WeDatabaseApi.addListener(this)

        // Hook 具体的网络回调
        hookReceiveCallback()
    }

    /**
     * 接口实现：处理数据库插入事件
     */
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type == TYPE_LUCKY_MONEY || type == TYPE_LUCKY_MONEY_EXCLUSIVE) {
            handleRedPacket(values)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val config = ConfigManager.getDefaultConfig()
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

            WeLogger.i("WeRedPacketAuto: 发现红包 sendId=$sendId")

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
            val customDelay = config.getStringPrek("red_packet_delay_custom", "0")?.toLongOrNull() ?: 0L
            val delayTime = if (isRandomDelay) Random.nextLong(500, 3000) else customDelay

            Thread {
                try {
                    if (delayTime > 0) Thread.sleep(delayTime)

                    // 直接使用 .clazz 访问器，自动反射
                    val req = XposedHelpers.newInstance(
                        dexClsReceiveLuckyMoney.clazz,
                        msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker
                    )
                    // 使用 NetworkApi 发送
                    WeNetworkApi.sendRequest(req)
                } catch (e: Throwable) {
                    WeLogger.e("WeRedPacketAuto: 发送拆包请求失败", e)
                }
            }.start()

        } catch (e: Throwable) {
            WeLogger.e("WeRedPacketAuto: 解析红包数据失败", e)
        }
    }

    private fun hookReceiveCallback() {
        try {
            // 直接使用 .method 访问器并 Hook
            dexMethodOnGYNetEnd.toDexMethod {
                hook {
                    afterIfEnabled { param ->
                        val json = param.args[2] as? JSONObject ?: return@afterIfEnabled
                        val sendId = json.optString("sendId")
                        val timingIdentifier = json.optString("timingIdentifier")

                        if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@afterIfEnabled

                        val info = currentRedPacketMap[sendId] ?: return@afterIfEnabled
                        WeLogger.i("WeRedPacketAuto: 拆包成功，准备开包 ($sendId)")

                        Thread {
                            try {
                                // 直接使用 .clazz 访问器
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
                                WeLogger.e("WeRedPacketAuto: 开包失败", e)
                            }
                        }.start()
                    }
                }
            }
        } catch (e: Throwable) {
            WeLogger.e("WeRedPacketAuto: Hook onGYNetEnd failed", e)
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
        WeDatabaseApi.removeListener(this)
        currentRedPacketMap.clear()
    }

    override fun onClick(context: Context?) {
        super.onClick(context)
        context?.let { WeRedPacketConfigDialog(it).show() }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找接收红包类
        dexClsReceiveLuckyMoney.find(dexKit, allowMultiple = true, descriptors) {
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
        val foundOpen = dexClsOpenLuckyMoney.find(dexKit, allowMultiple = true, descriptors) {
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
            WeLogger.e("WeRedPacketAuto: Failed to find OpenLuckyMoney class")
            throw RuntimeException("DexKit: Failed to find OpenLuckyMoney class with string 'MicroMsg.NetSceneOpenLuckyMoney'")
        }

        // 查找 onGYNetEnd 回调方法
        val receiveLuckyMoneyClassName = dexClsReceiveLuckyMoney.getDescriptorString()
        if (receiveLuckyMoneyClassName != null) {
            val foundMethod = dexMethodOnGYNetEnd.find(dexKit, allowMultiple = true, descriptors) {
                matcher {
                    declaredClass = receiveLuckyMoneyClassName
                    name = "onGYNetEnd"
                    paramCount = 3
                }
            }
            if (!foundMethod) {
                WeLogger.e("WeRedPacketAuto: Failed to find onGYNetEnd method")
                throw RuntimeException("DexKit: Failed to find onGYNetEnd method in $receiveLuckyMoneyClassName")
            }
        }

        return descriptors
    }
}