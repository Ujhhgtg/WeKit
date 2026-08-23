package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("unused")
@Keep
class ContactBean(
    @JvmField val origin: Any
) {
    @JvmField
    val username: String = origin.reflekt().getField("field_username", true) as? String ?: ""
    @JvmField
    val alias: String = origin.reflekt().getField("field_alias", true) as? String ?: ""
    @JvmField
    val conRemark: String = origin.reflekt().getField("field_conRemark", true) as? String ?: ""
    @JvmField
    val nickname: String = origin.reflekt().getField("field_nickname", true) as? String ?: ""

    fun getAlias(): String = alias
    fun getConRemark(): String = conRemark
    fun getNickname(): String = nickname
    fun getOrigin(): Any = origin
    fun getUsername(): String = username

    override fun toString(): String {
        return buildJsonObject {
            put("username", username)
            put("alias", alias)
            put("conRemark", conRemark)
            put("nickname", nickname)
        }.toString()
    }
}
