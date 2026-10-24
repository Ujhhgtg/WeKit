package dev.ujhhgtg.wekit.utils.serialization

sealed interface XmlValue

object XmlNull : XmlValue {
    override fun toString(): String = "null"
}

class XmlPrimitive(val value: String) : XmlValue {
    override fun toString(): String = value
}

class XmlObject(val properties: Map<String, XmlValue>) : XmlValue

class XmlArray(val elements: List<XmlValue>) : XmlValue, List<XmlValue> by elements

// --- Extension Helpers ---

val XmlValue.asString: String
    get() =
        when (this) {
            is XmlPrimitive -> value
            XmlNull -> "null"
            is XmlObject -> throw IllegalStateException("Cannot coerce XmlObject to String")
            is XmlArray -> throw IllegalStateException("Cannot coerce XmlArray to String")
        }

val XmlValue.asInt: Int
    get() = asString.toInt()
val XmlValue.asLong: Long
    get() = asString.toLong()
val XmlValue.asDouble: Double
    get() = asString.toDouble()
val XmlValue.asBoolean: Boolean
    get() = asString.toBooleanStrictOrNull() ?: false

operator fun XmlValue.get(key: String): XmlValue? =
    when (this) {
        is XmlObject -> properties[key]
        else -> null
    }

fun XmlValue.getByPath(path: String): XmlValue? {
    val segments = path.split('.')
    var current: XmlValue? = this
    for (segment in segments) {
        current =
            when (current) {
                is XmlObject -> current.properties[segment]
                else -> return null
            }
    }
    return current
}
