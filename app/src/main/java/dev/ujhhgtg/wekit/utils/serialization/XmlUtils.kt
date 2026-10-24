package dev.ujhhgtg.wekit.utils.serialization

import java.util.concurrent.ConcurrentHashMap

object XmlUtils {

    // 这些方法在消息处理热路径上被频繁调用, 编译好的 Regex 按名字缓存, 避免每次调用重新编译。
    private val attrRegexCache = ConcurrentHashMap<String, Regex>()
    private val cdataTagRegexCache = ConcurrentHashMap<String, Regex>()
    private val plainTagRegexCache = ConcurrentHashMap<String, Regex>()

    /** 属性名左侧必须是标签起始/空白, 否则形如 `md5` 的属性名会先匹配到 `androidmd5` / `externmd5` 这类同后缀属性, 取回错误的值。 */
    private fun attrRegex(attrName: String): Regex =
        attrRegexCache.getOrPut(attrName) {
            Regex("(?:^|[\\s<])" + Regex.escape(attrName) + "=\"([^\"]*)\"")
        }

    // 标签体可能跨行 (例如带换行的 title), 必须开启 DOT_MATCHES_ALL, 否则匹配不到返回空串。
    private fun cdataTagRegex(tagName: String): Regex =
        cdataTagRegexCache.getOrPut(tagName) {
            val name = Regex.escape(tagName)
            Regex("<$name><!\\[CDATA\\[(.*?)]]></$name>", RegexOption.DOT_MATCHES_ALL)
        }

    private fun plainTagRegex(tagName: String): Regex =
        plainTagRegexCache.getOrPut(tagName) {
            val name = Regex.escape(tagName)
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
        }

    /** 从 XML 提取属性值 (e.g. appid="xxx") */
    fun extractXmlAttr(xml: String, attrName: String): String {
        runCatching {
            val match = attrRegex(attrName).find(xml)
            return match?.groupValues?.get(1) ?: ""
        }
        return ""
    }

    /** 从 XML 提取标签内容 (e.g. <title>xxx</title>) */
    fun extractXmlTag(xml: String, tagName: String): String {
        runCatching {
            cdataTagRegex(tagName).find(xml)?.let {
                return it.groupValues[1]
            }
            plainTagRegex(tagName).find(xml)?.let {
                return it.groupValues[1]
            }
        }
        return ""
    }
}
