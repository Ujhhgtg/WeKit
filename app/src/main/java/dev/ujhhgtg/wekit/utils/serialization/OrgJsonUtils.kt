package dev.ujhhgtg.wekit.utils.serialization

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.toXml(rootTag: String = "root"): String = buildString {
    append("<$rootTag>")
    for (key in keys()) {
        val value = get(key)
        append(value.toXmlElement(key))
    }
    append("</$rootTag>")
}

private fun Any?.toXmlElement(tag: String): String = buildString {
    when (this@toXmlElement) {
        is JSONObject -> append(toXml(tag))
        is JSONArray -> append(toXml(tag))
        null,
        JSONObject.NULL -> append("<$tag/>")
        else -> append("<$tag>${toString().escapeXml()}</$tag>")
    }
}

private fun JSONArray.toXml(tag: String): String = buildString {
    for (i in 0 until length()) {
        append(get(i).toXmlElement(tag))
    }
}

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
