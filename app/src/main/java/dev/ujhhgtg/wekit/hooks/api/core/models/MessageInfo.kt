@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.hooks.api.core.models

import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.removeWxIdPrefix
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import dev.ujhhgtg.wekit.utils.serialization.XmlJsonParser
import dev.ujhhgtg.wekit.utils.serialization.asInt
import dev.ujhhgtg.wekit.utils.serialization.asLong
import dev.ujhhgtg.wekit.utils.serialization.asString
import dev.ujhhgtg.wekit.utils.serialization.get
import dev.ujhhgtg.wekit.utils.serialization.getByPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

class MessageInfo(val instance: Any) {

    val typeCode = getFieldByName<Int>(instance, "field_type")
    val type = MessageType.fromCode(typeCode)

    val id by lazy { getFieldByName<Long>(instance, "field_msgId") }
    val serverId by lazy { getFieldByName<Long>(instance, "field_msgSvrId") }
    val isSend by lazy { getFieldByName<Int>(instance, "field_isSend") }
    val createTime by lazy { getFieldByName<Long>(instance, "field_createTime") }
    val talker by lazy { getFieldByName<String>(instance, "field_talker") }
    val content by lazy { getFieldByName<String>(instance, "field_content") }

    val actualContent: String
        get() {
            var text = content
            if (isInGroupChat) {
                text = text.removeWxIdPrefix()
            }
            return text
        }

    val imagePath by lazy { getFieldByName<String>(instance, "field_imgPath") }
    val lvBuffer by lazy { getFieldByName<ByteArray>(instance, "field_lvbuffer") }
    val talkerId by lazy { getFieldByName<Int>(instance, "field_talkerId") }
    val seq by lazy { getFieldByName<Long>(instance, "field_msgSeq") }

    val isInGroupChat = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
    val isOfficialAccount = talker.startsWith("gh_")
    val sender by lazy {
        @Suppress("DEPRECATION")
        if (typeCode == MessageType.SYSTEM.code) {
            return@lazy "system"
        }

        if (typeCode == MessageType.PAT.code) {
            val patMsg = PatMessage(content)
            return@lazy patMsg.fromUser
        }

        if (isSelfSender) {
            return@lazy WeApi.selfWxId
        }

        if (!isInGroupChat) {
            return@lazy talker
        }

        return@lazy content.split(':')[0]
    }

    val isSelfSender get() = isSend != 0

    inline fun toPatMessage(): PatMessage? {
        if (typeCode != MessageType.PAT.code)
            return null

        return PatMessage(content)
    }

    fun toQuoteMessage(): QuoteMessage? {
        if (typeCode != MessageType.QUOTE.code)
            return null

        return QuoteMessage(content)
    }

    fun toTransferMessage(): TransferMessage? {
        if (type != MessageType.TRANSFER)
            return null

        return TransferMessage(content)
    }

    class PatMessage(jsonString: String) {

        private val json = DefaultJson.parseToJsonElement(jsonString)
        val createTime by lazy { recordObj["createTime"]!!.asLong }
        val fromUser by lazy { recordObj["fromUser"]!!.asString }
        val pattedUser by lazy { recordObj["pattedUser"]!!.asString }
        val readStatus by lazy { recordObj["readStatus"]!!.asInt }
        val recordNum by lazy { json.getByPath("msg.appmsg.patMsg.records.recordNum")!!.asInt }
        val showModifyTip by lazy { recordObj["showModifyTip"]!!.asInt }
        val svrId by lazy { recordObj["svrId"]!!.asLong }
        val talker by lazy { json.getByPath("msg.appmsg.patMsg.chatUser")!!.asString }
        val template by lazy { recordObj["template"]!!.asString }
        val recordObj: JsonElement by lazy {
            val byPath = json.getByPath("msg.appmsg.patMsg.records.record")!!
            if (byPath is JsonArray) {
                return@lazy byPath.jsonArray[0]
            }
            return@lazy byPath
        }
    }

    class QuoteMessage(jsonString: String) {
        private val json = XmlJsonParser.toJsonObject(jsonString.cleanupXml())

        val title by lazy { json.getByPath("msg.appmsg.title")!!.asString }
    }

    class TransferMessage(jsonString: String) {

        private val json = XmlJsonParser.toJsonObject(jsonString.cleanupXml())

        // 'transcationid' is WeChat's typo
        val transactionId by lazy { json.getByPath("msg.wcpayinfo.transcationid")!!.asString }
        val transferId by lazy { json.getByPath("msg.wcpayinfo.transferid")!!.asString }

        // FIXME: payerUsername is empty
        //        val payerUsername by lazy { json.getByPath("msg.wcpayinfo.payer_username")!!.asString }
        val invalidTime by lazy { json.getByPath("msg.wcpayinfo.invalidtime")!!.asString.toInt() }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> getFieldByName(instance: Any, name: String): T {
            return instance.asResolver()
                .firstField {
                    this.name = name
                    superclass()
                }
                .get()!! as T
        }

        private fun String.cleanupXml(): String {
            return "<msg>" + substringAfter("<msg>")
                .substringBeforeLast("</msg>")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", "")
                .replace("<?xml version=\"1.0\"?>", "") + "</msg>"
        }
    }
}
