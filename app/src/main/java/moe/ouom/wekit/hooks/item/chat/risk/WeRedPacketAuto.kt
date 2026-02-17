package moe.ouom.wekit.hooks.item.chat.risk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.core.net.toUri
import com.afollestad.materialdialogs.MaterialDialog
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
import moe.ouom.wekit.ui.creator.dialog.item.chat.risk.WeRedPacketConfigDialog
import moe.ouom.wekit.util.log.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天与消息/自动抢红包", desc = "监听消息并自动拆开红包")
class WeRedPacketAuto : BaseClickableFunctionHookItem(), WeDatabaseListener.DatabaseInsertListener,
    IDexFind {

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
        WeLogger.i("WeRedPacketAuto: entry() 被调用，开始注册数据库监听")
        // 注册数据库监听
        WeDatabaseListener.addListener(this)
        WeLogger.i("WeRedPacketAuto: 数据库监听器已注册")

        // Hook 具体的网络回调
        hookReceiveCallback()
        WeLogger.i("WeRedPacketAuto: 网络回调 Hook 完成")
    }

    /**
     * 接口实现：处理数据库插入事件
     */
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type == TYPE_LUCKY_MONEY || type == TYPE_LUCKY_MONEY_EXCLUSIVE) {
            WeLogger.i("WeRedPacketAuto: 检测到红包消息 type=$type")
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
            val customDelay =
                config.getStringPrek("red_packet_delay_custom", "0")?.toLongOrNull() ?: 0L

            WeLogger.i("WeRedPacketAuto: 配置读取 - isRandomDelay=$isRandomDelay, customDelay=$customDelay")

            // 如果开启随机延迟，在自定义延迟基础上增加随机偏移
            val delayTime = if (isRandomDelay) {
                val baseDelay = if (customDelay > 0) customDelay else 1000L
                val randomOffset = Random.nextLong(-500, 500)
                val finalDelay = (baseDelay + randomOffset).coerceAtLeast(0)
                WeLogger.i("WeRedPacketAuto: 随机延迟模式 - baseDelay=$baseDelay, randomOffset=$randomOffset, finalDelay=$finalDelay")
                finalDelay
            } else {
                WeLogger.i("WeRedPacketAuto: 固定延迟模式 - delayTime=$customDelay")
                customDelay
            }

            Thread {
                try {
                    WeLogger.i("WeRedPacketAuto: 开始延迟 ${delayTime}ms (sendId=$sendId)")
                    if (delayTime > 0) Thread.sleep(delayTime)

                    WeLogger.i("WeRedPacketAuto: 延迟结束，准备发送拆包请求 (sendId=$sendId)")
                    val req = XposedHelpers.newInstance(
                        dexClsReceiveLuckyMoney.clazz,
                        msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker
                    )

                    WeNetworkApi.sendRequest(req)
                    WeLogger.i("WeRedPacketAuto: 拆包请求已发送 (sendId=$sendId)")
                } catch (e: Throwable) {
                    WeLogger.e("WeRedPacketAuto: 发送拆包请求失败 (sendId=$sendId)", e)
                }
            }.start()

        } catch (e: Throwable) {
            WeLogger.e("WeRedPacketAuto: 解析红包数据失败", e)
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
                        WeLogger.i("WeRedPacketAuto: 拆包成功，准备开包 ($sendId)")

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
        WeLogger.i("WeRedPacketAuto: unload() 被调用，移除数据库监听")
        WeDatabaseListener.removeListener(this)
        currentRedPacketMap.clear()
        WeLogger.i("WeRedPacketAuto: 数据库监听器已移除，红包缓存已清空")
        super.unload(classLoader)  // 必须调用父类方法来重置 isLoad 标志
    }

    override fun onClick(context: Context?) {
        context?.let { WeRedPacketConfigDialog(it).show() }
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
            WeLogger.e("WeRedPacketAuto: Failed to find OpenLuckyMoney class")
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
                WeLogger.e("WeRedPacketAuto: Failed to find onGYNetEnd method")
                throw RuntimeException("DexKit: Failed to find onGYNetEnd method in $receiveLuckyMoneyClassName")
            }
        }

        return descriptors
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            MaterialDialog(context)
                .title(text = "警告")
                .message(text = "此功能可能导致账号异常，确定要启用吗?")
                .positiveButton(text = "确定") { _ ->
                    applyToggle(true)
                }
                .negativeButton(text = "取消") { dialog ->
                    dialog.dismiss()
                }
                .show()

            // 返回 false 阻止自动切换
            return false
        }

        // 禁用功能时直接允许
        return true
    }
}