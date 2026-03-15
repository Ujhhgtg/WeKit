package moe.ouom.wekit.utils

import java.util.regex.Pattern

object XmlUtils {


    /**
     * 从 XML 提取属性值 (e.g. appid="xxx")
     */
    fun extractXmlAttr(xml: String, attrName: String): String {
        runCatching {
            val pattern = Pattern.compile("$attrName=\"([^\"]*)\"")
            val matcher = pattern.matcher(xml)
            if (matcher.find()) {
                return matcher.group(1) ?: ""
            }
        }
        return ""
    }

    /**
     * 从 XML 提取标签内容 (e.g. <title>xxx</title>)
     */
    fun extractXmlTag(xml: String, tagName: String): String {
        runCatching {
            val pattern = Pattern.compile("<$tagName><!\\[CDATA\\[(.*?)]]></$tagName>")
            val matcher = pattern.matcher(xml)
            if (matcher.find()) {
                return matcher.group(1) ?: ""
            }
            // Fallback for non-CDATA
            val patternSimple = Pattern.compile("<$tagName>(.*?)</$tagName>")
            val matcherSimple = patternSimple.matcher(xml)
            if (matcherSimple.find()) {
                return matcherSimple.group(1) ?: ""
            }
        }
        return ""
    }
}