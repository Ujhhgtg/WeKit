package moe.ouom.wekit.hooks.sdk.protocol

import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.hooks.sdk.protocol.MsgIdProvider.previewNextId
import moe.ouom.wekit.hooks.sdk.protocol.WeApi.generateClientMsgId
import moe.ouom.wekit.hooks.sdk.protocol.WeApi.getSelfWxId
import moe.ouom.wekit.hooks.sdk.protocol.intf.ISigner
import moe.ouom.wekit.hooks.sdk.protocol.model.SignResult
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONObject

/**
 * 消息发送签名器 (CGI 522)
 */
class NewSendMsgSigner : ISigner {
    override fun match(cgiId: Int) = (cgiId == 522)
    override fun sign(loader: ClassLoader, json: JSONObject): SignResult {
        val selfWxid = getSelfWxId()

        fun applySign(item: JSONObject) {
            val ts = System.currentTimeMillis()
            item.put("4", (ts / 1000).toInt())
            item.put("5", generateClientMsgId(selfWxid, ts))
        }

        val list = json.optJSONArray("2")
        if (list != null) {
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { applySign(it) }
        } else json.optJSONObject("2")?.let { applySign(it) }

        return SignResult(json)
    }
}

/**
 * AppMsg 签名注入 (CGI 222)
 */
class AppMsgSigner : ISigner {
    override fun match(cgiId: Int) = (cgiId == 222)
    override fun sign(loader: ClassLoader, json: JSONObject): SignResult {
        val innerMsg = json.optJSONObject("2") ?: return SignResult(json)
        val toUser = innerMsg.optString("4")

        val nextId = previewNextId("message")
        val nowMs = System.currentTimeMillis()

        val signature = "$toUser${nextId}T$nowMs"

        innerMsg.put("8", signature)       // u8.ClientMsgId
        innerMsg.put("7", (nowMs / 1000).toInt()) // u8.CreateTime
        json.put("7", signature)           // lr5.Signature
        json.put("4", (nowMs / 1000).toInt()) // lr5.ReqTime

        WeLogger.i("AppMsgSigner", "成功: ID=$nextId, Sign=$signature")
        return SignResult(json)
    }
}

/**
 * 表情签名器 (CGI 175)
 */
class EmojiSigner : ISigner {
    override fun match(cgiId: Int) = (cgiId == 175)
    override fun sign(loader: ClassLoader, json: JSONObject): SignResult {
        val tag3Obj = json.optJSONObject("3")
        if (tag3Obj != null) {
            val ts = System.currentTimeMillis().toString()
            tag3Obj.put("9", ts)
        }
        return SignResult(json)
    }
}

/**
 * 拍一拍签名器 (CGI 849)
 */
class SendPatSigner(private val clsProvider: () -> Class<*>?) : ISigner {
    override fun match(cgiId: Int) = (cgiId == 849)

    override fun sign(loader: ClassLoader, json: JSONObject): SignResult {
        val cls = clsProvider() ?: return SignResult(json)

        try {
            val toUser = json.optString("3")
            val pattedUser = json.optString("4")
            val scene = json.optInt("6")

            // wxid_xxxxx_761663_1770315448000
            val validPair = android.util.Pair(previewNextId("message"), System.currentTimeMillis())

            val nativeScene = XposedHelpers.newInstance(
                cls,
                validPair,   // Pair
                toUser,      // String
                pattedUser,  // String
                scene        // int
            )
            return SignResult(json, nativeNetScene = nativeScene)
        } catch (e: Throwable) {
            WeLogger.e("SendPatSigner", "实例化原生 NetScene 失败: ${e.message}")
            return SignResult(json)
        }
    }
}