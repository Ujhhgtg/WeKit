package dev.ujhhgtg.wekit.hooks.api.core.model

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.utils.DefaultJson
import dev.ujhhgtg.wekit.utils.asInt
import dev.ujhhgtg.wekit.utils.asLong
import dev.ujhhgtg.wekit.utils.asString
import dev.ujhhgtg.wekit.utils.getByPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class MessageInfo(val instance: Any) {

    @Suppress("UNCHECKED_CAST")
    private fun <T> getFieldByName(instance: Any, name: String): T {
        return instance.asResolver()
            .firstField {
                this.name = name
                superclass()
            }
            .get()!! as T
    }

    val type by lazy { getFieldByName<Int>(instance, "field_type") }
    val id by lazy { getFieldByName<Long>(instance, "field_msgId") }
    val serverId by lazy { getFieldByName<Long>(instance, "field_msgSvrId") }
    val isSend by lazy { getFieldByName<Int>(instance, "field_isSend") }
    val createTime by lazy { getFieldByName<Long>(instance, "field_createTime") }
    val talker by lazy { getFieldByName<String>(instance, "field_talker") }
    val content by lazy { getFieldByName<String>(instance, "field_content") }
    val imagePath by lazy { getFieldByName<String>(instance, "field_imgPath") }
    val lvBuffer by lazy { getFieldByName<ByteArray>(instance, "field_lvbuffer") }
    val talkerId by lazy { getFieldByName<Int>(instance, "field_talkerId") }
    val seq by lazy { getFieldByName<Long>(instance, "field_msgSeq") }

    val isInGroupChat = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
    val isOfficialAccount = talker.startsWith("gh_")
    val sender by lazy {
        if (isType(MessageType.SYSTEM)) {
            return@lazy "system"
        }

        if (isType(MessageType.PAT)) {
            val patMsg = PatMessage(content)
            return@lazy patMsg.fromUser
                ?: throw IllegalArgumentException("could not determine pat message's from user")
        }

        if (isSelfSender()) {
            return@lazy WeDatabaseApi.getSelfProfileField(SelfProfileField.WXID) as String
        }

        if (!isInGroupChat) {
            return@lazy talker
        }

        return@lazy content.split(':')[0]
    }

    fun isSelfSender(): Boolean {
        return isSend == 1
    }

    fun isType(type: MessageType): Boolean {
        return this.type == type.code
    }

    val isText = isType(MessageType.TEXT) || isType(MessageType.TEXT_WITH_QUOTE)

    class PatMessage(jsonString: String) {

        private val json = DefaultJson.parseToJsonElement(jsonString)
        val createTime by lazy { this.recordObj?.getByPath("createTime")?.asLong }
        val fromUser by lazy { this.recordObj?.getByPath("fromUser")?.asString }
        val pattedUser by lazy { this.recordObj?.getByPath("pattedUser")?.asString }
        val readStatus by lazy { this.recordObj?.getByPath("readStatus")?.asInt }
        val recordNum by lazy { json.getByPath("msg.appmsg.patMsg.records.recordNum")?.jsonPrimitive?.intOrNull }
        val showModifyTip by lazy { this.recordObj?.getByPath("showModifyTip")?.asInt }
        val svrId by lazy { this.recordObj?.getByPath("svrId")?.asLong }
        val talker by lazy { json.getByPath("msg.appmsg.patMsg.chatUser")?.asString }
        val template by lazy { this.recordObj?.getByPath("template")?.asString }
        val recordObj: JsonElement? by lazy {
            val byPath = this.json.getByPath("msg.appmsg.patMsg.records.record") ?: return@lazy null
            if (byPath is JsonArray) {
                return@lazy byPath.jsonArray[0]
            }
            return@lazy byPath
        }
    }
}
