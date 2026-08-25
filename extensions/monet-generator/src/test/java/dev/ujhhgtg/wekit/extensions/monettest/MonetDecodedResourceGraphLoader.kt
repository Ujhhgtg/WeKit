package dev.ujhhgtg.wekit.extensions.monettest

import dev.ujhhgtg.wekit.extensions.monet.MonetConfiguredValue
import dev.ujhhgtg.wekit.extensions.monet.MonetRawXmlAttribute
import dev.ujhhgtg.wekit.extensions.monet.MonetRawXmlChild
import dev.ujhhgtg.wekit.extensions.monet.MonetRawXmlElement
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceGraph
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceKey
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceNode
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceValue
import dev.ujhhgtg.wekit.extensions.monet.normalizeMonetXml
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class MonetDecodedResourceGraph(
    val graph: MonetResourceGraph,
    val binaryXmlShapesComparable: Boolean,
    val limitations: List<String>,
)

/** Desktop-only loader for apktool/Android Studio decoded `app/src/main/res` trees. */
internal object MonetDecodedResourceGraphLoader {
    fun load(resourceDir: File): MonetDecodedResourceGraph {
        require(resourceDir.isDirectory) { "decoded resource path is not a directory: $resourceDir" }
        val root = resourceDir.canonicalFile.toPath()
        val publicXml = root.resolve("values/public.xml")
        require(Files.isRegularFile(publicXml)) {
            "decoded resources are missing values/public.xml: $resourceDir"
        }

        val resourcesByKey = linkedMapOf<MonetResourceKey, MutableDecodedResource>()
        val resourcesById = linkedMapOf<Int, MonetResourceKey>()
        parseXml(publicXml).documentElement.childElements("public").forEach { element ->
            val key = MonetResourceKey(
                type = element.requiredAttribute("type"),
                name = element.requiredAttribute("name"),
            )
            val id = parseResourceId(element.requiredAttribute("id"))
            require(resourcesByKey.putIfAbsent(key, MutableDecodedResource(id, key)) == null) {
                "duplicate public resource key: ${key.type}/${key.name}"
            }
            require(resourcesById.putIfAbsent(id, key) == null) {
                "duplicate public resource ID 0x${id.toUInt().toString(16)}"
            }
        }
        require(resourcesByKey.isNotEmpty()) { "values/public.xml contains no public resources" }

        regularFiles(root)
            .filter { path ->
                path.fileName.toString() == "colors.xml" &&
                    path.parent?.fileName?.toString()?.startsWith("values") == true
            }
            .sortedBy { root.relativize(it).toString() }
            .forEach { colorsXml ->
                val directory = colorsXml.parent.fileName.toString()
                val qualifiers = directory.removePrefix("values").removePrefix("-")
                parseXml(colorsXml).documentElement.childElements("color").forEach { element ->
                    val key = MonetResourceKey("color", element.requiredAttribute("name"))
                    val resource = requireNotNull(resourcesByKey[key]) {
                        "decoded color ${key.name} is absent from values/public.xml"
                    }
                    resource.addValue(
                        qualifiers,
                        parseValue(
                            element.textContent.trim(),
                            resourcesByKey,
                            resourcesById,
                            allowDecodedLiteral = false,
                        ),
                        colorsXml,
                    )
                }
            }

        val decodedXml = mutableListOf<DecodedXml>()
        regularFiles(root)
            .filter { path ->
                path.fileName.toString().endsWith(".xml") &&
                    path.parent?.fileName?.toString()?.substringBefore('-') in XML_RESOURCE_TYPES
            }
            .sortedBy { root.relativize(it).toString() }
            .forEach { xmlPath ->
                val directory = xmlPath.parent.fileName.toString()
                val type = directory.substringBefore('-')
                val qualifiers = directory.removePrefix(type).removePrefix("-")
                val name = xmlPath.fileName.toString().removeSuffix(".xml")
                val key = MonetResourceKey(type, name)
                val resource = requireNotNull(resourcesByKey[key]) {
                    "decoded XML $type/$name is absent from values/public.xml"
                }
                val relativePath = root.relativize(xmlPath).joinToString("/") { it.toString() }
                resource.addValue(
                    qualifiers,
                    MonetResourceValue.File("res/$relativePath"),
                    xmlPath,
                )
                val references = linkedSetOf<Int>()
                decodedXml += DecodedXml(
                    ownerId = resource.id,
                    root = parseElement(
                        parseXml(xmlPath).documentElement,
                        resourcesByKey,
                        resourcesById,
                        references,
                    ),
                    references = references,
                )
            }

        var graph = MonetResourceGraph(resourcesByKey.values.map(MutableDecodedResource::toNode))
        decodedXml.groupBy(DecodedXml::ownerId).forEach { (ownerId, documents) ->
            graph = graph.withXmlData(
                sourceId = ownerId,
                referenceIds = documents.flatMapTo(linkedSetOf(), DecodedXml::references),
                shapes = documents.mapTo(linkedSetOf()) { document ->
                    normalizeMonetXml(document.root, graph::referenceSignature)
                },
            )
        }
        return MonetDecodedResourceGraph(
            graph = graph,
            binaryXmlShapesComparable = false,
            limitations = listOf(
                "decoded text XML does not preserve compiled attribute resource IDs",
                "decoded decompiler XML may contain unbound namespace prefixes and is parsed namespace-agnostically",
                "decoded resources do not reproduce the installed base/split resource-table digest boundary",
            ),
        )
    }

    private fun parseElement(
        element: Element,
        resourcesByKey: Map<MonetResourceKey, MutableDecodedResource>,
        resourcesById: Map<Int, MonetResourceKey>,
        references: MutableSet<Int>,
    ): MonetRawXmlElement {
        val attributes = buildList {
            for (index in 0 until element.attributes.length) {
                val attribute = element.attributes.item(index)
                if (attribute.nodeName == "xmlns" || attribute.nodeName.startsWith("xmlns:")) continue
                val parsed = parseValue(
                    attribute.nodeValue,
                    resourcesByKey,
                    resourcesById,
                    allowDecodedLiteral = true,
                )
                if (parsed is MonetResourceValue.Reference) references += parsed.resourceId
                add(
                    MonetRawXmlAttribute(
                        namespace = attribute.namespaceURI,
                        name = attribute.localName ?: attribute.nodeName.substringAfter(':'),
                        nameId = null,
                        valueType = parsed.valueTypeName(),
                        value = parsed,
                    ),
                )
            }
        }
        val children = buildList {
            for (index in 0 until element.childNodes.length) {
                val child = element.childNodes.item(index)
                when (child.nodeType) {
                    Node.ELEMENT_NODE -> add(
                        MonetRawXmlChild.Element(
                            parseElement(child as Element, resourcesByKey, resourcesById, references),
                        ),
                    )
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> child.nodeValue
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { add(MonetRawXmlChild.Text(it)) }
                }
            }
        }
        return MonetRawXmlElement(
            name = element.localName ?: element.tagName.substringAfter(':'),
            namespace = element.namespaceURI,
            attributes = attributes,
            children = children,
        )
    }

    private fun parseValue(
        rawValue: String,
        resourcesByKey: Map<MonetResourceKey, MutableDecodedResource>,
        resourcesById: Map<Int, MonetResourceKey>,
        allowDecodedLiteral: Boolean,
    ): MonetResourceValue {
        val value = rawValue.trim()
        parseColor(value)?.let { return it }
        when (value) {
            "@android:color/black" -> return MonetResourceValue.Reference(android.R.color.black)
            "@android:color/white" -> return MonetResourceValue.Reference(android.R.color.white)
            "@null" -> return MonetResourceValue.Reference(0)
        }
        if (value.startsWith('@') && !value.startsWith("@android:")) {
            val reference = value.removePrefix("@").removePrefix("+")
            if (reference.startsWith("0x")) {
                val resourceId = parseResourceId(reference)
                require(resourceId in resourcesById) {
                    "decoded numeric resource reference is absent from values/public.xml: $value"
                }
                return MonetResourceValue.Reference(resourceId)
            }
            val pieces = reference.split('/', limit = 2)
            require(pieces.size == 2) { "unsupported decoded resource reference: $value" }
            val key = MonetResourceKey(pieces[0], pieces[1])
            val target = requireNotNull(resourcesByKey[key]) {
                "decoded resource reference is absent from values/public.xml: $value"
            }
            return MonetResourceValue.Reference(target.id)
        }
        if (value == "true" || value == "false") {
            return MonetResourceValue.Literal("INT_BOOLEAN", if (value == "true") 1 else 0)
        }
        value.toLongOrNull()?.let { return MonetResourceValue.Literal("INT_DEC", it) }
        if (value.startsWith("0x")) {
            value.removePrefix("0x").toULongOrNull(16)?.let {
                return MonetResourceValue.Literal("INT_HEX", it.toLong())
            }
        }
        if (value.endsWith(".xml") && '/' in value) return MonetResourceValue.File(value)
        require(allowDecodedLiteral) { "unsupported decoded color value: $value" }
        return MonetResourceValue.File("decoded:$value")
    }

    private fun parseColor(value: String): MonetResourceValue.Literal? {
        if (!value.startsWith('#')) return null
        val digits = value.removePrefix("#")
        val (type, argb) = when (digits.length) {
            3 -> "COLOR_RGB4" to ("ff" + digits.map { "$it$it" }.joinToString(""))
            4 -> "COLOR_ARGB4" to digits.map { "$it$it" }.joinToString("")
            6 -> "COLOR_RGB8" to "ff$digits"
            8 -> "COLOR_ARGB8" to digits
            else -> error("invalid decoded color literal: $value")
        }
        return MonetResourceValue.Literal(type, argb.toULong(16).toLong())
    }

    private fun MonetResourceValue.valueTypeName(): String = when (this) {
        is MonetResourceValue.Literal -> valueType
        is MonetResourceValue.Reference -> valueType
        is MonetResourceValue.File -> "DECODED_LITERAL"
        is MonetResourceValue.Complex -> "DECODED_COMPLEX"
    }

    private fun parseResourceId(value: String): Int {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val parsed = if (normalized.startsWith("0x")) {
            normalized.removePrefix("0x").toULong(16)
        } else {
            normalized.toULong()
        }
        require(parsed <= UInt.MAX_VALUE.toULong()) { "resource ID is outside 32 bits: $value" }
        return parsed.toUInt().toInt()
    }

    private fun parseXml(path: Path) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    }.newDocumentBuilder().parse(path.toFile()).also { document ->
        require(document.documentElement.tagName.isNotBlank()) { "decoded XML has no root: $path" }
    }

    private fun regularFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile)
            .peek { path ->
                require(path.toRealPath().startsWith(root)) { "decoded resource escapes root: $path" }
            }
            .toList()
    }

    private fun Element.childElements(name: String): List<Element> = buildList {
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child.nodeType == Node.ELEMENT_NODE &&
                ((child as Element).localName ?: child.tagName) == name
            ) {
                add(child)
            }
        }
    }

    private fun Element.requiredAttribute(name: String): String = getAttribute(name).also { value ->
        require(value.isNotBlank()) { "<$tagName> is missing $name" }
    }

    private data class MutableDecodedResource(
        val id: Int,
        val key: MonetResourceKey,
        val values: MutableMap<String, MonetResourceValue> = linkedMapOf(),
    ) {
        fun addValue(qualifiers: String, value: MonetResourceValue, source: Any) {
            val existing = values[qualifiers]
            require(existing == null || existing == value) {
                "conflicting decoded values for ${key.type}/${key.name} qualifiers '$qualifiers' in $source"
            }
            if (existing == null) values[qualifiers] = value
        }

        fun toNode() = MonetResourceNode(
            id = id,
            key = key,
            values = values.toSortedMap().map { (qualifiers, value) ->
                MonetConfiguredValue(qualifiers, value)
            },
        )
    }

    private data class DecodedXml(
        val ownerId: Int,
        val root: MonetRawXmlElement,
        val references: Set<Int>,
    )

    private val XML_RESOURCE_TYPES = setOf("drawable", "layout")
}
