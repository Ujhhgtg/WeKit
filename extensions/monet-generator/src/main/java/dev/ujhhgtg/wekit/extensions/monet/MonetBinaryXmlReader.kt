package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlTextNode

internal data class MonetBinaryXml(
    val root: MonetRawXmlElement,
    val referenceIds: Set<Int>,
) {
    fun shape(referenceSignature: (Int) -> MonetReferenceSignature?): MonetXmlShape =
        normalizeMonetXml(root, referenceSignature)
}

internal object MonetBinaryXmlReader {
    fun read(document: ResXmlDocument): MonetBinaryXml {
        val referenceIds = linkedSetOf<Int>()
        val root = document.getElements().asSequence().firstOrNull()
            ?: error("binary XML document has no root element")
        return MonetBinaryXml(
            root = root.toMonetElement(referenceIds),
            referenceIds = referenceIds.toSet(),
        )
    }

    private fun ResXmlElement.toMonetElement(referenceIds: MutableSet<Int>): MonetRawXmlElement =
        MonetRawXmlElement(
            name = name,
            namespace = uri,
            attributes = attributes.asSequence().map { attribute ->
                val valueType = requireNotNull(attribute.valueType) {
                    "binary XML attribute ${attribute.name} has no value type"
                }
                val value = if (valueType.isReference) {
                    MonetResourceValue.Reference(attribute.data).also {
                        referenceIds += attribute.data
                    }
                } else {
                    MonetResourceValue.Literal(
                        valueType = valueType.typeName,
                        data = Integer.toUnsignedLong(attribute.data),
                    )
                }
                MonetRawXmlAttribute(
                    namespace = attribute.uri,
                    name = attribute.name,
                    nameId = attribute.nameId.takeIf { it != 0 },
                    valueType = valueType.typeName,
                    value = value,
                )
            }.toList(),
            children = iterator().asSequence().mapNotNull { child ->
                when (child) {
                    is ResXmlElement -> MonetRawXmlChild.Element(child.toMonetElement(referenceIds))
                    is ResXmlTextNode -> if (child.isComment) {
                        null
                    } else {
                        child.text?.let(MonetRawXmlChild::Text)
                    }
                    else -> error("unsupported binary XML node ${child.javaClass.name}")
                }
            }.toList(),
        )
}
