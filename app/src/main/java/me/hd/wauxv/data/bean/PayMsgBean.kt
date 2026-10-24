package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import dev.ujhhgtg.reflekt.spec.typeMatches
import dev.ujhhgtg.wekit.utils.reflection.int

@Keep
class PayMsgBean(g2: Any) {
    val origin: Any = g2
    val username: String
    val displayName: String
    val fee: Double
    val timestamp: Int
    val status: Int
    val statusDesc: String

    init {
        val fields = g2.javaClass.declaredFields
        fields.forEach { it.isAccessible = true }

        val doubleField = fields.firstOrNull {
            it.type == Double::class.java || it.type == Double::class.javaPrimitiveType
        }
        fee = doubleField?.get(g2) as? Double ?: 0.0

        val stringFields = fields.filter { it.type == String::class.java }
        username = if (stringFields.isNotEmpty()) stringFields[0].get(g2) as? String ?: "" else ""
        displayName = if (stringFields.size > 2) stringFields[2].get(g2) as? String ?: "" else ""

        val intFields = fields.filter { it.type.typeMatches(int) }
        timestamp = if (intFields.isNotEmpty()) intFields[0].get(g2) as? Int ?: 0 else 0
        status = if (intFields.size > 2) intFields[2].get(g2) as? Int ?: 0 else 0

        statusDesc =
            when (status) {
                0 -> "支付中..."
                1 -> "支付成功"
                2 -> "取消支付"
                else -> "未知状态"
            }
    }

    override fun toString(): String {
        return org.json
            .JSONObject()
            .apply {
                put("timestamp", timestamp)
                put("username", username)
                put("displayName", displayName)
                put("fee", fee)
                put("status", status)
                put("statusDesc", statusDesc)
            }
            .toString()
    }
}
