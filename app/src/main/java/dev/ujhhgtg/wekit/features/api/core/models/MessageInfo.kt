@file:Suppress("NOTHING_TO_INLINE", "unused")

package dev.ujhhgtg.wekit.features.api.core.models

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import dev.ujhhgtg.wekit.utils.serialization.NativeXmlParser
import dev.ujhhgtg.wekit.utils.serialization.asInt
import dev.ujhhgtg.wekit.utils.serialization.asLong
import dev.ujhhgtg.wekit.utils.serialization.asString
import dev.ujhhgtg.wekit.utils.serialization.get
import dev.ujhhgtg.wekit.utils.serialization.getByPath
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import dev.ujhhgtg.wekit.utils.strings.stripWxId
import java.nio.ByteBuffer
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
                text = text.stripWxId()
            }
            return text
        }

    val quoteMsgActualContent: String?
        get() {
            val quoteMsg = toQuoteMessage() ?: return null

            var text = quoteMsg.title
            if (isInGroupChat) {
                text = text.stripWxId()
            }

            return text
        }

    val imagePath by lazy { getFieldByName<String?>(instance, "field_imgPath") }
    val lvBuffer by lazy { getFieldByName<ByteArray>(instance, "field_lvbuffer") }
    val talkerId by lazy { getFieldByName<Int>(instance, "field_talkerId") }
    val seq by lazy { getFieldByName<Long>(instance, "field_msgSeq") }

    val msgSource: String by lazy {
        val buffer = lvBuffer
        if (buffer.isEmpty()) return@lazy ""
        if (buffer[0] != '{'.code.toByte() || buffer.last() != '}'.code.toByte()) return@lazy ""

        val bb = ByteBuffer.wrap(buffer)
        bb.position(1) // skip '{'

        // skip string field (2-byte length prefix + data)
        if (bb.remaining() >= 2) {
            val n1 = bb.short.toInt()
            if (n1 > 3072) error("Buffer String Length Error")
            if (n1 != 0 && bb.remaining() >= n1) bb.position(bb.position() + n1)
        }

        // skip 4-byte int field
        if (bb.remaining() >= 4) bb.position(bb.position() + 4)

        if (bb.remaining() < 2) return@lazy ""

        val n2 = bb.short.toInt()
        if (n2 > 3072) error("Buffer String Length Error")
        if (n2 == 0 || bb.remaining() < n2) return@lazy ""

        val bytes = ByteArray(n2)
        bb.get(bytes)
        String(bytes, Charsets.UTF_8)
    }

    val mentionedUsers: List<String> by lazy {
        if (msgSource.isEmpty()) return@lazy emptyList()
        val xml = try {
            NativeXmlParser.toXmlObject(msgSource)
        } catch (_: Exception) {
            return@lazy emptyList()
        }
        val atUserListStr = xml.getByPath("msgsource.atuserlist")?.asString ?: return@lazy emptyList()
        atUserListStr.split(",").filter { it.isNotEmpty() }
    }

    val isAtMe get() = mentionedUsers.contains(WeApi.selfWxId)

    val isAnnounceAll get() = mentionedUsers.contains("announcement@all")

    val isNotifyAll: Boolean
        get() {
            if (mentionedUsers.contains("notify@all") || mentionedUsers.contains("announcement@all")) {
                val contentText = actualContent
                return contentText.contains("@所有人") || contentText.contains("@ all people")
            }
            return false
        }

    val isInGroupChat = talker.isGroupChatWxId
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

    fun toFileMessage(): FileMessage? {
        if (type != MessageType.FILE)
            return null

        return FileMessage(content)
    }

    fun toImageMessage(): ImageMessage? {
        if (type != MessageType.IMAGE)
            return null

        return ImageMessage(content)
    }

    class FileMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val size by lazy { xml.getByPath("msg.appmsg.appattach.totallen")!!.asLong }
        val ext by lazy { xml.getByPath("msg.appmsg.appattach.fileext")!!.asString }
        val md5 by lazy { xml.getByPath("msg.appmsg.md5")!!.asString }
        val url by lazy { xml.getByPath("msg.appmsg.appattach.cdnattachurl")!!.asString }
        val key by lazy { xml.getByPath("msg.appmsg.appattach.aeskey")!!.asString }
    }

    class ImageMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val md5 by lazy { xml.getByPath("msg.img.md5")!!.asString }
        val bigImgUrl by lazy { xml.getByPath("msg.img.cdnbigimgurl")!!.asString }
        val midImgUrl by lazy { xml.getByPath("msg.img.cdnmidimgurl")!!.asString }
        val thumbUrl by lazy { xml.getByPath("msg.img.cdnthumburl")!!.asString }
        val aesKey by lazy { xml.getByPath("msg.img.aeskey")!!.asString }
    }

    class PatMessage(jsonStr: String) {

        private val json = DefaultJson.parseToJsonElement(jsonStr)

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

    class QuoteMessage(xmlStr: String) {
        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val chatusr by lazy { xml.getByPath("msg.appmsg.refermsg.chatusr")!!.asString }
        val displayname by lazy { xml.getByPath("msg.appmsg.refermsg.displayname")!!.asString }
        val msgsource by lazy { xml.getByPath("msg.appmsg.refermsg.msgsource")!!.asString }
        val svrid by lazy { xml.getByPath("msg.appmsg.refermsg.svrid")!!.asString.toLong() }
        val fromusr by lazy { xml.getByPath("msg.appmsg.refermsg.fromusr")!!.asString }
        val type by lazy { xml.getByPath("msg.appmsg.refermsg.type")!!.asString.toInt() }
        val content by lazy { xml.getByPath("msg.appmsg.refermsg.content")!!.asString }
    }

    class TransferMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val des by lazy { xml.getByPath("msg.appmsg.des")!!.asString }
        // 'transcationid' is WeChat's typo
        val transactionId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transcationid")!!.asString }
        val transferId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transferid")!!.asString }
        val beginTransferTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.begintransfertime")!!.asString.toLong() }
        val payerUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.payer_username")!!.asString }
        val receiverUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.receiver_username")!!.asString }
        val invalidTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.invalidtime")!!.asString.toInt() }
        val feedesc by lazy { xml.getByPath("msg.appmsg.wcpayinfo.feedesc")!!.asString }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <T> getFieldByName(instance: Any, name: String): T {
            return instance.reflekt().getField(name, true) as T
        }

        /**
         * Safely extracts tag content directly from raw XML strings.
         * Bypasses JSON type coercion overhead and prevents 32-digit string truncation.
         */
        private fun extractXmlTag(xml: String, tag: String): String? {
            val startTag = "<$tag>"
            val endTag = "</$tag>"
            if (!xml.contains(startTag) || !xml.contains(endTag)) return null

            val content = xml.substringAfter(startTag).substringBefore(endTag)
            return if (content.startsWith("<![CDATA[")) {
                content.substringAfter("<![CDATA[").substringBefore("]]>")
            } else {
                content
            }.trim()
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
