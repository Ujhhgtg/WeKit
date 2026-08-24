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
                        val ownerIds = resFile.asSequence()
                            .filter { it.packageBlock.name == targetPackage }
                            .map { it.resourceId }
                            .toSet()
                        if (ownerIds.isNotEmpty()) {
                            xmlDocuments += OwnedXml(
                                ownerIds = ownerIds,
                                xml = MonetBinaryXmlReader.read(
                                    module.loadResXmlDocument(resFile.inputSource),
                                ),
                            )
                        }
                    }
            }
        }

        var graph = MonetResourceGraph(resources.values.map(MutableResource::toNode))
        val xmlReferences = linkedMapOf<Int, MutableSet<Int>>()
        xmlDocuments.forEach { ownedXml ->
            ownedXml.xml.shape(graph::referenceSignature)
            ownedXml.ownerIds.forEach { ownerId ->
                xmlReferences.getOrPut(ownerId, ::linkedSetOf).addAll(ownedXml.xml.referenceIds)
            }
        }
        xmlReferences.forEach { (sourceId, referenceIds) ->
            graph = graph.withXmlReferences(sourceId, referenceIds)
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
            val values = entry.allValues().asSequence().map { it.toMonetValue() }.toList()
            val existing = merged.valuesByQualifiers[qualifiers]
            require(existing == null || existing == values) {
                "conflicting values for 0x${id.toUInt().toString(16)} ($key) qualifiers '$qualifiers' in $apk"
            }
            if (existing == null) merged.valuesByQualifiers[qualifiers] = values
        }
    }

    private fun ValueItem.toMonetValue(): MonetResourceValue {
        val valueType = requireNotNull(valueType) { "ARSC value has no value type" }
        if (valueType.isReference) return MonetResourceValue.Reference(data)
        if (valueType == ValueType.STRING) {
            val stringValue = valueAsString
            if (stringValue != null && stringValue.startsWith("res/")) {
                return MonetResourceValue.File(stringValue)
            }
        }
        return MonetResourceValue.Literal(
            valueType = valueType.typeName,
            data = Integer.toUnsignedLong(data),
        )
    }

    private fun String.isMonetXmlPath(): Boolean =
        endsWith(".xml") && (startsWith("res/layout") || startsWith("res/drawable"))

    private data class MutableResource(
        val id: Int,
        val key: MonetResourceKey,
        val valuesByQualifiers: MutableMap<String, List<MonetResourceValue>> = linkedMapOf(),
    ) {
        fun toNode() = MonetResourceNode(
            id = id,
            key = key,
            values = valuesByQualifiers.toSortedMap().flatMap { (qualifiers, values) ->
                values.map { value -> MonetConfiguredValue(qualifiers, value) }
            },
        )
    }

    private data class OwnedXml(
        val ownerIds: Set<Int>,
        val xml: MonetBinaryXml,
    )
}
