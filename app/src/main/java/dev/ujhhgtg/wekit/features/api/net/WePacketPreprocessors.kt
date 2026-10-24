package dev.ujhhgtg.wekit.features.api.net

import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.net.MsgIdPreviewer.generateClientMsgId
import dev.ujhhgtg.wekit.features.api.net.MsgIdPreviewer.previewNextId
import dev.ujhhgtg.wekit.features.api.net.abc.IPacketPreprocessor
import dev.ujhhgtg.wekit.features.api.net.models.PreprocessResult
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.AppMsgItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.EmojiItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.IAppMsgProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.INewSendMsgProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.ISendEmojiProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.ISendPatProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgItemProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NewSendMsgReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendAppMsgReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendEmojiReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SendPatReqProto
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONObject

/** 消息发送签名器 (CGI 522) */
object NewSendMsgSigner : IPacketPreprocessor {
    override fun matchesJson(cgiId: Int) = cgiId == 522

    override fun matchesProto(value: Any): Boolean = value is INewSendMsgProto

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> preprocessProto(value: T): T {
        val ts = System.currentTimeMillis()
        val nowSec = (ts / 1000).toInt()
        val selfWxId = runCatching { WeApi.selfWxId }.getOrNull() ?: ""
        val clientMsgId = generateClientMsgId(selfWxId, ts)

        return when (value) {
            is NewSendMsgReqProto -> {
                val updatedItems =
                    value.items.map { item ->
                        val itemCreateTime = if (item.createTime == 0) nowSec else item.createTime
                        val itemClientMsgId =
                            if (item.clientMsgId == 0) clientMsgId else item.clientMsgId
                        item.copy(createTime = itemCreateTime, clientMsgId = itemClientMsgId)
                    }
                val reqCreateTime = if (value.createTime == 0) nowSec else value.createTime
                val reqClientMsgId = if (value.clientMsgId == 0) clientMsgId else value.clientMsgId
                value.copy(
                    items = updatedItems,
                    createTime = reqCreateTime,
                    clientMsgId = reqClientMsgId,
                ) as T
            }
            is NewSendMsgItemProto -> {
                val itemCreateTime = if (value.createTime == 0) nowSec else value.createTime
                val itemClientMsgId = if (value.clientMsgId == 0) clientMsgId else value.clientMsgId
                value.copy(createTime = itemCreateTime, clientMsgId = itemClientMsgId) as T
            }
            else -> value
        }
    }

    override fun preprocessJson(cl: ClassLoader, json: JSONObject): PreprocessResult {
        fun applySign(item: JSONObject) {
            val ts = System.currentTimeMillis()
            val selfWxId = runCatching { WeApi.selfWxId }.getOrNull() ?: ""
            item.put("4", (ts / 1000).toInt())
            item.put("5", generateClientMsgId(selfWxId, ts))
        }

        val list = json.optJSONArray("2")
        if (list != null) {
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { applySign(it) }
        } else json.optJSONObject("2")?.let { applySign(it) }

        return PreprocessResult(json = json)
    }
}

/** AppMsg 签名注入 (CGI 222) */
object AppMsgSigner : IPacketPreprocessor {
    override fun matchesJson(cgiId: Int) = cgiId == 222

    override fun matchesProto(value: Any): Boolean = value is IAppMsgProto

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> preprocessProto(value: T): T {
        val nextId = previewNextId("message")
        val nowMs = System.currentTimeMillis()
        val nowSec = (nowMs / 1000).toInt()

        return when (value) {
            is SendAppMsgReqProto -> {
                val toUser = value.msg.toUserName
                val signature = value.signature.ifEmpty { "$toUser${nextId}T$nowMs" }
                val reqTime = if (value.reqTime == 0) nowSec else value.reqTime
                val itemCreateTime = if (value.msg.createTime == 0) nowSec else value.msg.createTime
                val itemClientMsgId = value.msg.clientMsgId.ifEmpty { signature }
                val updatedMsg =
                    value.msg.copy(
                        createTime = itemCreateTime,
                        clientMsgId = itemClientMsgId,
                    )
                value.copy(
                    msg = updatedMsg,
                    reqTime = reqTime,
                    signature = signature,
                ) as T
            }
            is AppMsgItemProto -> {
                val toUser = value.toUserName
                val signature = value.clientMsgId.ifEmpty { "$toUser${nextId}T$nowMs" }
                val itemCreateTime = if (value.createTime == 0) nowSec else value.createTime
                value.copy(
                    createTime = itemCreateTime,
                    clientMsgId = signature,
                ) as T
            }
            else -> value
        }
    }

    override fun preprocessJson(cl: ClassLoader, json: JSONObject): PreprocessResult {
        val innerMsg = json.optJSONObject("2") ?: return PreprocessResult(json = json)
        val toUser = innerMsg.optString("4")

        val nextId = previewNextId("message")
        val nowMs = System.currentTimeMillis()

        val signature = "$toUser${nextId}T$nowMs"

        innerMsg.put("8", signature) // u8.ClientMsgId
        innerMsg.put("7", (nowMs / 1000).toInt()) // u8.CreateTime
        json.put("7", signature) // lr5.Signature
        json.put("4", (nowMs / 1000).toInt()) // lr5.ReqTime

        WeLogger.i("AppMsgSigner", "成功: ID=$nextId, Sign=$signature")
        return PreprocessResult(json = json)
    }
}

/** 表情签名器 (CGI 175) */
object EmojiSigner : IPacketPreprocessor {
    override fun matchesJson(cgiId: Int) = cgiId == 175

    override fun matchesProto(value: Any): Boolean = value is ISendEmojiProto

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> preprocessProto(value: T): T {
        val nowStr = System.currentTimeMillis().toString()

        return when (value) {
            is SendEmojiReqProto -> {
                val updatedList =
                    value.emojiList.map { item ->
                        val clientMsgId = item.clientMsgId.ifEmpty { nowStr }
                        item.copy(clientMsgId = clientMsgId)
                    }
                value.copy(
                    count = if (value.count == 0) updatedList.size else value.count,
                    emojiList = updatedList,
                ) as T
            }
            is EmojiItemProto -> {
                val clientMsgId = value.clientMsgId.ifEmpty { nowStr }
                value.copy(clientMsgId = clientMsgId) as T
            }
            else -> value
        }
    }

    override fun preprocessJson(cl: ClassLoader, json: JSONObject): PreprocessResult {
        val tag3Obj = json.optJSONObject("3")
        if (tag3Obj != null) {
            val ts = System.currentTimeMillis().toString()
            tag3Obj.put("9", ts)
        }
        return PreprocessResult(json = json)
    }
}

/** 拍一拍签名器 (CGI 849) */
class SendPatSigner(private val lazyClass: () -> Class<*>?) : IPacketPreprocessor {
    override fun matchesJson(cgiId: Int) = cgiId == 849

    override fun matchesProto(value: Any): Boolean = value is ISendPatProto

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> preprocessProto(value: T): T {
        return when (value) {
            is SendPatReqProto -> {
                val fromUser =
                    value.fromUser.ifEmpty {
                        runCatching { WeApi.selfWxId }.getOrNull().orEmpty()
                    }
                val msgPointer =
                    value.msgPointer.ifEmpty {
                        val nextId = previewNextId("message")
                        val nowMs = System.currentTimeMillis()
                        "$nextId,$nowMs"
                    }
                value.copy(
                    fromUser = fromUser,
                    msgPointer = msgPointer,
                ) as T
            }
            else -> value
        }
    }

    override fun preprocessJson(cl: ClassLoader, json: JSONObject): PreprocessResult {
        val cls = lazyClass() ?: return PreprocessResult(json = json)

        try {
            val chatUserName = json.optString("3")
            val pattedUser = json.optString("4")
            val scene = json.optInt("6")

            val validPair = android.util.Pair(previewNextId("message"), System.currentTimeMillis())

            val nativeScene =
                cls.createInstance(
                    validPair,
                    chatUserName,
                    pattedUser,
                    scene,
                )
            return PreprocessResult(json = json, nativeNetScene = nativeScene)
        } catch (e: Throwable) {
            WeLogger.e("SendPatSigner", "实例化原生 NetScene 失败: ${e.message}")
            return PreprocessResult(json = json)
        }
    }
}

object WePacketSigner {
    val signers: List<IPacketPreprocessor> by lazy {
        listOf(
            NewSendMsgSigner,
            EmojiSigner,
            AppMsgSigner,
            SendPatSigner { WePacketHelper.classNetScenePat.clazz },
        )
    }

    fun <T : Any> preprocess(value: T): T {
        var current = value
        for (signer in signers) {
            if (signer.matchesProto(current)) {
                current = signer.preprocessProto(current)
            }
        }
        return current
    }
}
