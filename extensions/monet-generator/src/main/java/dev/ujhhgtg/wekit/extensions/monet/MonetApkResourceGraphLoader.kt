package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.File

internal object MonetApkResourceGraphLoader {
    fun load(apkPaths: List<File>, targetPackage: String): MonetResourceGraph {
        val resources = linkedMapOf<Int, MutableResource>()
        val xmlDocuments = mutableListOf<OwnedXml>()

        apkPaths.forEach { apk ->
            ApkModule.loadApkFile(apk).apply { setLoadDefaultFramework(false) }.use { module ->
                module.tableBlock.listPackages()
                    .filter { it.name == targetPackage }
                    .forEach { packageBlock ->
                        packageBlock.getResources().asSequence().forEach { resource ->
                            resources.merge(resource, apk)
                        }
                    }

                module.listResFiles()
                    .asSequence()
                    .filter { it.filePath.isMonetXmlPath() && it.isBinaryXml }
                    .forEach { resFile ->
                        val owners = resFile.asSequence()
                            .filter { it.packageBlock.name == targetPackage }
                            .map { entry ->
                                XmlIdentity(entry.resourceId, entry.resConfig.qualifiers, resFile.filePath)
                            }
                            .toList()
                        if (owners.isNotEmpty()) {
                            val xml = MonetBinaryXmlReader.read(
                                module.loadResXmlDocument(resFile.inputSource),
                            )
                            owners.forEach { identity -> xmlDocuments += OwnedXml(identity, xml) }
                        }
                    }
            }
        }

        var graph = MonetResourceGraph(resources.values.map(MutableResource::toNode))
        val definitions = linkedMapOf<XmlIdentity, XmlDefinition>()
        xmlDocuments.forEach { ownedXml ->
            val definition = XmlDefinition(
                shape = ownedXml.xml.shape(graph::referenceSignature),
                referenceIds = ownedXml.xml.referenceIds,
            )
            val existing = definitions[ownedXml.identity]
            require(existing == null || existing == definition) {
                "conflicting binary XML for ${ownedXml.identity}"
            }
            if (existing == null) definitions[ownedXml.identity] = definition
        }
        val xmlReferences = linkedMapOf<Int, MutableSet<Int>>()
        val xmlShapes = linkedMapOf<Int, MutableSet<MonetXmlShape>>()
        definitions.forEach { (identity, definition) ->
            xmlReferences.getOrPut(identity.ownerId, ::linkedSetOf).addAll(definition.referenceIds)
            xmlShapes.getOrPut(identity.ownerId, ::linkedSetOf).add(definition.shape)
        }
        (xmlReferences.keys + xmlShapes.keys).forEach { sourceId ->
            graph = graph.withXmlData(
                sourceId,
                xmlReferences[sourceId].orEmpty(),
                xmlShapes[sourceId].orEmpty(),
            )
        }
        return graph
    }

    private fun MutableMap<Int, MutableResource>.merge(resource: ResourceEntry, apk: File) {
        if (resource.isEmpty) return
        val id = resource.resourceId
        val key = MonetResourceKey(
            type = requireNotNull(resource.type) { "resource 0x${id.toUInt().toString(16)} has no type" },
            name = requireNotNull(resource.name) { "resource 0x${id.toUInt().toString(16)} has no name" },
        )
        val merged = getOrPut(id) { MutableResource(id, key) }
        require(merged.key == key) {
            "resource 0x${id.toUInt().toString(16)} changes identity from ${merged.key} to $key in $apk"
        }
        resource.asSequence().forEach { entry ->
            val qualifiers = entry.resConfig.qualifiers
            val value = if (entry.isComplex) {
                val complex = requireNotNull(entry.resTableMapEntry) {
                    "complex ARSC entry 0x${id.toUInt().toString(16)} has no map entry"
                }
                MonetResourceValue.Complex(
                    parentId = complex.parentId,
                    items = complex.iterator().asSequence().map { item ->
                        MonetComplexValue(item.nameId, item.toMonetValue())
                    }.toList(),
                )
            } else {
                requireNotNull(entry.resValue) {
                    "scalar ARSC entry 0x${id.toUInt().toString(16)} has no value"
                }.toMonetValue()
            }
            val existing = merged.valuesByQualifiers[qualifiers]
            require(existing == null || existing == value) {
                "conflicting values for 0x${id.toUInt().toString(16)} ($key) qualifiers '$qualifiers' in $apk"
            }
            if (existing == null) merged.valuesByQualifiers[qualifiers] = value
        }
    }

    private fun ValueItem.toMonetValue(): MonetResourceValue {
        val valueType = requireNotNull(valueType) { "ARSC value has no value type" }
        if (valueType.isReference) return MonetResourceValue.Reference(data, valueType.name)
        if (valueType == ValueType.STRING) {
            val stringValue = valueAsString
            if (stringValue != null && stringValue.startsWith("res/")) {
                return MonetResourceValue.File(stringValue)
            }
        }
        return MonetResourceValue.Literal(
            valueType = valueType.name,
            data = Integer.toUnsignedLong(data),
        )
    }

    private fun String.isMonetXmlPath(): Boolean =
        endsWith(".xml") && (startsWith("res/layout") || startsWith("res/drawable"))

    private data class MutableResource(
        val id: Int,
        val key: MonetResourceKey,
        val valuesByQualifiers: MutableMap<String, MonetResourceValue> = linkedMapOf(),
    ) {
        fun toNode() = MonetResourceNode(
            id = id,
            key = key,
            values = valuesByQualifiers.toSortedMap().map { (qualifiers, value) ->
                MonetConfiguredValue(qualifiers, value)
            },
        )
    }

    private data class XmlIdentity(
        val ownerId: Int,
        val qualifiers: String,
        val path: String,
    )

    private data class XmlDefinition(
        val shape: MonetXmlShape,
        val referenceIds: Set<Int>,
    )

    private data class OwnedXml(
        val identity: XmlIdentity,
        val xml: MonetBinaryXml,
    )
}
