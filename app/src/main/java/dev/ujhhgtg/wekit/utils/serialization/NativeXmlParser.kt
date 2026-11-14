package dev.ujhhgtg.wekit.utils.serialization

import java.io.Reader

object NativeXmlParser {
    @Suppress("NOTHING_TO_INLINE")
    inline fun toXmlObject(
        string: String,
        configuration: XmlParserConfiguration = XmlParserConfiguration(),
    ): XmlObject = toXmlObject(string.reader(), configuration)

    fun toXmlObject(
        reader: Reader,
        config: XmlParserConfiguration = XmlParserConfiguration(),
    ): XmlObject {
        val context = XmlAccumulator()
        val tokener = XmlTokener(reader, config)
        while (tokener.more()) {
            tokener.skipPast("<")
            if (tokener.more()) {
                parse(tokener, context, null, config, 0)
            }
        }
        return context.toXmlObject(config.forceList)
    }

    private fun parse(
        x: XmlTokener,
        context: XmlAccumulator,
        name: String?,
        config: XmlParserConfiguration,
        currentNestingDepth: Int,
    ): Boolean {
        var token: Any? = x.nextToken()

        when (token) {
            '!' -> {
                val c = x.next()
                if (c == '-') {
                    if (x.next() == '-') {
                        x.skipPast("-->")
                        return false
                    }
                    x.back()
                } else if (c == '[') {
                    token = x.nextToken()
                    if ("CDATA" == token) {
                        if (x.next() == '[') {
                            val text = x.nextCDATA()
                            if (text.isNotEmpty()) {
                                context.accumulate(config.cDataTagName, XmlPrimitive(text))
                            }
                            return false
                        }
                    }
                    throw x.syntaxError("Expected 'CDATA['")
                }

                var depth = 1
                do {
                    token = x.nextMeta()
                    when (token) {
                        '<' -> depth += 1
                        '>' -> depth -= 1
                    }
                } while (depth > 0)
                return false
            }

            '?' -> {
                x.skipPast("?>")
                return false
            }

            '/' -> {
                val closeName = x.nextToken()
                if (name == null || closeName != name) {
                    throw x.syntaxError("Mismatched close tag $closeName")
                }
                if (x.nextToken() != '>') {
                    throw x.syntaxError("Misshaped close tag")
                }
                return true
            }

            is Char -> {
                throw x.syntaxError("Misshaped tag")
            }

            else -> {
                val tagName = token as String
                token = null
                val elementObj = XmlAccumulator()
                var nilAttributeFound = false

                while (true) {
                    if (token == null) {
                        token = x.nextToken()
                    }

                    if (token is String) {
                        val attrName = token
                        token = x.nextToken()

                        if (token == '=') {
                            token = x.nextToken()
                            if (token !is String) throw x.syntaxError("Missing value")
                            val attrValue = token
                            if (
                                config.convertNilAttributeToNull &&
                                    attrName == "nil" &&
                                    attrValue.toBooleanStrictOrNull() == true
                            ) {
                                nilAttributeFound = true
                            } else if (!nilAttributeFound) {
                                elementObj.accumulate(attrName, XmlPrimitive(attrValue))
                            }
                            token = null
                        } else {
                            elementObj.accumulate(attrName, XmlPrimitive(""))
                        }
                    } else if (token == '/') {
                        if (x.nextToken() != '>') throw x.syntaxError("Misshaped tag")
                        val emptyValue: XmlValue =
                            if (nilAttributeFound) {
                                XmlNull
                            } else if (elementObj.isEmpty()) {
                                XmlPrimitive("")
                            } else {
                                elementObj.toXmlValue(config.forceList)
                            }

                        if (tagName in config.forceList) {
                            if (nilAttributeFound) {
                                context.append(tagName, XmlNull)
                            } else if (elementObj.isEmpty()) {
                                context.put(tagName, XmlArray(emptyList()))
                            } else {
                                context.append(tagName, emptyValue)
                            }
                        } else {
                            context.accumulate(tagName, emptyValue)
                        }
                        return false
                    } else if (token == '>') {
                        while (true) {
                            token = x.nextContent()
                            when (token) {
                                null -> {
                                    throw x.syntaxError("Unclosed tag $tagName")
                                }

                                is String -> {
                                    val text = token
                                    if (text.isNotEmpty()) {
                                        elementObj.accumulate(
                                            config.cDataTagName,
                                            XmlPrimitive(text),
                                        )
                                    }
                                }

                                '<' -> {
                                    if (currentNestingDepth == config.maxNestingDepth) {
                                        throw x.syntaxError("Maximum nesting depth reached")
                                    }
                                    if (
                                        parse(
                                            x,
                                            elementObj,
                                            tagName,
                                            config,
                                            currentNestingDepth + 1,
                                        )
                                    ) {
                                        val value: XmlValue? =
                                            if (elementObj.isEmpty()) {
                                                null
                                            } else if (elementObj.size == 1) {
                                                elementObj.singleValue(config.cDataTagName)
                                            } else {
                                                null
                                            }
                                        if (tagName in config.forceList) {
                                            when {
                                                elementObj.isEmpty() -> {
                                                    context.put(tagName, XmlArray(emptyList()))
                                                }

                                                value != null -> {
                                                    context.append(tagName, value)
                                                }

                                                else -> {
                                                    if (!config.trimWhiteSpace) {
                                                        elementObj.pruneEmptyStrings()
                                                    }
                                                    context.append(
                                                        tagName,
                                                        elementObj.toXmlValue(config.forceList),
                                                    )
                                                }
                                            }
                                        } else {
                                            when {
                                                elementObj.isEmpty() -> {
                                                    context.accumulate(tagName, XmlPrimitive(""))
                                                }

                                                value != null -> {
                                                    context.accumulate(tagName, value)
                                                }

                                                else -> {
                                                    if (!config.trimWhiteSpace) {
                                                        elementObj.pruneEmptyStrings()
                                                    }
                                                    context.accumulate(
                                                        tagName,
                                                        elementObj.toXmlValue(config.forceList),
                                                    )
                                                }
                                            }
                                        }
                                        return false
                                    }
                                }

                                else -> {
                                    throw x.syntaxError("Misshaped tag")
                                }
                            }
                        }
                    } else {
                        throw x.syntaxError("Misshaped tag")
                    }
                }
            }
        }
    }

    private class XmlAccumulator {
        private val values = linkedMapOf<String, MutableList<XmlValue>>()

        val size: Int
            get() = values.size

        fun isEmpty(): Boolean = values.isEmpty()

        fun accumulate(name: String, value: XmlValue) {
            values.getOrPut(name) { mutableListOf() }.add(value)
        }

        fun put(name: String, value: XmlValue) {
            values[name] = mutableListOf(value)
        }

        fun append(name: String, value: XmlValue) {
            accumulate(name, value)
        }

        fun singleValue(name: String): XmlValue? {
            val list = values[name] ?: return null
            return when (list.size) {
                0 -> null
                1 -> list[0]
                else -> XmlArray(list)
            }
        }

        fun toXmlValue(forceList: Set<String>): XmlValue {
            //            if (values.size == 1) {
            //                val (key, list) = values.entries.first()
            //                return if (key in forceList || list.size > 1) XmlArray(list) else
            // list[0]
            //            }
            return toXmlObject(forceList)
        }

        fun toXmlObject(forceList: Set<String>): XmlObject {
            val map = linkedMapOf<String, XmlValue>()
            for ((key, list) in values) {
                map[key] = if (key in forceList || list.size > 1) XmlArray(list) else list[0]
            }
            return XmlObject(map)
        }

        fun pruneEmptyStrings() {
            for ((_, list) in values) {
                list.removeAll { element -> element is XmlPrimitive && element.value.isEmpty() }
            }
            values.entries.removeAll { it.value.isEmpty() }
        }
    }
}
