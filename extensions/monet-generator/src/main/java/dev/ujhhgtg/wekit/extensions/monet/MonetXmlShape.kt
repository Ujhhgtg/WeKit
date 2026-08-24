package dev.ujhhgtg.wekit.extensions.monet

import java.security.MessageDigest

internal data class MonetRawXmlElement(
    val name: String,
    val attributes: List<MonetRawXmlAttribute> = emptyList(),
    val children: List<MonetRawXmlChild> = emptyList(),
)

internal data class MonetRawXmlAttribute(
    val namespace: String?,
    val name: String,
    val nameId: Int?,
    val valueType: String,
    val value: MonetResourceValue,
)

internal sealed interface MonetRawXmlChild {
    data class Element(val value: MonetRawXmlElement) : MonetRawXmlChild
    data class Text(val value: String) : MonetRawXmlChild
}

internal data class MonetXmlShape(val sha256: String)

internal fun normalizeMonetXml(
    element: MonetRawXmlElement,
    referenceSignature: (Int) -> MonetReferenceSignature? = { null },
): MonetXmlShape {
    val canonical = buildString { appendElement(element, referenceSignature) }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return MonetXmlShape(digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
}

private fun StringBuilder.appendElement(
    element: MonetRawXmlElement,
    referenceSignature: (Int) -> MonetReferenceSignature?,
) {
    appendToken("element")
    appendToken(element.name)
    element.attributes
        .sortedWith(compareBy<MonetRawXmlAttribute>(
            { it.namespace.orEmpty() },
            { it.nameId ?: Int.MIN_VALUE },
            { it.name },
            { it.valueType },
            { it.value.normalized(referenceSignature) },
        ))
        .forEach { attribute ->
            appendToken("attribute")
            appendToken(attribute.namespace.orEmpty())
            appendToken(attribute.nameId?.toString().orEmpty())
            appendToken(attribute.name)
            appendToken(attribute.valueType)
            appendToken(attribute.value.normalized(referenceSignature))
        }
    element.children.forEach { child ->
        when (child) {
            is MonetRawXmlChild.Element -> appendElement(child.value, referenceSignature)
            is MonetRawXmlChild.Text -> {
                appendToken("text")
                appendToken(child.value)
            }
        }
    }
    appendToken("end")
}

private fun MonetResourceValue.normalized(
    referenceSignature: (Int) -> MonetReferenceSignature?,
): String = when (this) {
    is MonetResourceValue.Literal -> "literal:$valueType:$data"
    is MonetResourceValue.File -> "file:$path"
    is MonetResourceValue.Reference -> referenceSignature(resourceId)?.let {
        "reference:${it.type}:${it.defaultValue ?: "-"}:${it.nightValue ?: "-"}"
    } ?: if (resourceId.ushr(24) == 0x01) {
        "framework:$resourceId"
    } else {
        "reference:$resourceId"
    }
}

private fun StringBuilder.appendToken(value: String) {
    append(value.length).append(':').append(value)
}
