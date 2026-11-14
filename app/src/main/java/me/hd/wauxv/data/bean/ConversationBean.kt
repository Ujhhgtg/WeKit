package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.reflekt

@Suppress("unused")
@Keep
class ConversationBean(@JvmField val origin: Any) {
    @JvmField
    val username: String = origin.reflekt().getField("field_username", true) as? String ?: ""

    @JvmField
    val unReadCount: Int =
        (origin.reflekt().getField("field_unReadCount", true) as? Number)?.toInt() ?: 0

    @JvmField
    val msgCount: Int = (origin.reflekt().getField("field_msgCount", true) as? Number)?.toInt() ?: 0

    @JvmField
    val isSendInt: Int = (origin.reflekt().getField("field_isSend", true) as? Number)?.toInt() ?: 0

    @JvmField
    val conversationTime: Long =
        (origin.reflekt().getField("field_conversationTime", true) as? Number)?.toLong() ?: 0L

    @JvmField
    val content: String = origin.reflekt().getField("field_content", true) as? String ?: ""

    @JvmField
    val msgType: String = origin.reflekt().getField("field_msgType", true) as? String ?: ""

    @JvmField
    val flag: Long = (origin.reflekt().getField("field_flag", true) as? Number)?.toLong() ?: 0L

    @JvmField val digest: String = origin.reflekt().getField("field_digest", true) as? String ?: ""

    @JvmField
    val digestUser: String = origin.reflekt().getField("field_digestUser", true) as? String ?: ""

    @JvmField
    val parentRef: String = origin.reflekt().getField("field_parentRef", true) as? String ?: ""

    fun getUsername(): String = username

    fun getUnReadCount(): Int = unReadCount

    fun getMsgCount(): Int = msgCount

    fun isSendInt(): Int = isSendInt

    fun isSend(): Boolean = isSendInt == 1

    fun getConversationTime(): Long = conversationTime

    fun getContent(): String = content

    fun getMsgType(): String = msgType

    fun getFlag(): Long = flag

    fun getDigest(): String = digest

    fun getDigestUser(): String = digestUser

    fun getParentRef(): String = parentRef

    fun getOrigin(): Any = origin
}
