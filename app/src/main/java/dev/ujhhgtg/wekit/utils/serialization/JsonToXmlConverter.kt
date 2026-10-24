package dev.ujhhgtg.wekit.utils.serialization

import android.util.Xml
import java.io.IOException
import java.io.StringWriter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlSerializer

data class XmlAttribute(val name: String, val value: String)

class XmlNode(var tagName: String?, val path: String) {
    var textContent: String? = null
    val attributes: MutableList<XmlAttribute> = mutableListOf()
    val children: MutableList<XmlNode> = mutableListOf()
}

class JsonToXmlConverter(
    val json: JSONObject,
    val attributePaths: HashSet<String>,
    val textPaths: HashSet<String>,
) {
    companion object {
        val decimalFormat = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    }

    private fun serializeNode(serializer: XmlSerializer, node: XmlNode) {
        val tag = node.tagName
        if (tag != null) {
            serializer.startTag("", tag)
            node.attributes.forEach { serializer.attribute("", it.name, it.value) }
            node.textContent?.let { serializer.text(it) }
        }
        node.children.forEach { serializeNode(serializer, it) }
        if (tag != null) serializer.endTag("", tag)
    }

    private fun processArray(parent: XmlNode, key: String, array: JSONArray) {
        val childPath = "${parent.path}/$key"
        for (i in 0 until array.length()) {
            val child = XmlNode(key, childPath)
            when (val item = array.opt(i) ?: continue) {
                is JSONObject -> processObject(child, item)
                is JSONArray -> processArray(child, key, item)
                else -> {
                    child.tagName = key
                    child.textContent = item.toString()
                }
            }
            parent.children.add(child)
        }
    }

    private fun processObject(node: XmlNode, obj: JSONObject) {
        for (key in obj.keys()) {
            val value = obj.opt(key) ?: continue
            when (value) {
                is JSONObject -> {
                    val child = XmlNode(key, "${node.path}/$key")
                    node.children.add(child)
                    processObject(child, value)
                }

                is JSONArray -> processArray(node, key, value)
                else -> {
                    val strValue =
                        when (value) {
                            is Double if value % 1.0 == 0.0 -> value.toLong().toString()
                            is Double -> {
                                if (decimalFormat.maximumFractionDigits == 0)
                                    decimalFormat.maximumFractionDigits = 20
                                decimalFormat.format(value)
                            }

                            else -> value.toString()
                        }
                    val valuePath = "${node.path}/$key"
                    when (valuePath) {
                        in attributePaths -> node.attributes.add(XmlAttribute(key, strValue))
                        in textPaths -> node.textContent = strValue
                        else ->
                            node.children.add(
                                XmlNode(key, node.path).also { it.textContent = strValue },
                            )
                    }
                }
            }
        }
    }

    override fun toString(): String {
        val root = XmlNode(null, "")
        processObject(root, json)
        val serializer = Xml.newSerializer()
        val writer = StringWriter()
        try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializeNode(serializer, root)
            serializer.endDocument()
            return writer.toString()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }
}
