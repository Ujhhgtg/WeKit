package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

object MonetApkResourceGraphLoader {
    fun load(
        apkPaths: List<File>,
        targetPackage: String,
        onProgress: (detail: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): MonetResourceGraph {
        val resources = linkedMapOf<Int, MutableResource>()
        val xmlDocuments = mutableListOf<OwnedXml>()

        apkPaths.forEachIndexed { index, apk ->
            onProgress("打开 ${apk.name}", index, apkPaths.size)
            ApkModule.loadApkFile(apk).apply { setLoadDefaultFramework(false) }.use { module ->
                val resFiles = module.listResFiles().toList()
                val fileStructures = resFiles.associate { it.filePath to it.fileStructure() }
                onProgress("解析 ${apk.name} 的资源表", index, apkPaths.size)
                module.tableBlock.listPackages()
                    .filter { it.name == targetPackage }
                    .forEach { packageBlock ->
                        packageBlock.getResources().asSequence().forEach { resource ->
                            resources.merge(resource, apk, fileStructures)
                        }
                    }

                onProgress("解析 ${apk.name} 的 ${resFiles.count { it.isBinaryXml }} 个二进制 XML", index, apkPaths.size)
                resFiles.asSequence()
                    .forEach { resFile ->
                        val owners = resFile.asSequence()
                            .filter {
                                it.packageBlock.name == targetPackage &&
                                    it.typeName in MONET_XML_RESOURCE_TYPES
                            }
                            .map { entry ->
                                XmlIdentity(entry.resourceId, entry.resConfig.qualifiers, resFile.filePath)
                            }
                            .toList()
                        if (owners.isNotEmpty() && resFile.isBinaryXml) {
                            val xml = MonetBinaryXmlReader.read(
                                module.loadResXmlDocument(resFile.inputSource),
                            )
                            owners.forEach { identity -> xmlDocuments += OwnedXml(identity, xml) }
                        }
                    }
            }
            onProgress("完成 ${apk.name}", index + 1, apkPaths.size)
        }

        val definitions = linkedMapOf<XmlIdentity, MonetXmlElement>()
        xmlDocuments.forEach { ownedXml ->
            val definition = ownedXml.xml.root
            val existing = definitions[ownedXml.identity]
            require(existing == null || existing == definition) {
                "conflicting binary XML for ${ownedXml.identity}"
            }
            if (existing == null) definitions[ownedXml.identity] = definition
        }
        val xmlByOwner = definitions.entries.groupBy(
            keySelector = { it.key.ownerId },
            valueTransform = Map.Entry<XmlIdentity, MonetXmlElement>::value,
        )
        return MonetResourceGraph(resources.values.map(MutableResource::toNode), xmlByOwner)
    }

    private fun MutableMap<Int, MutableResource>.merge(
        resource: ResourceEntry,
        apk: File,
        fileStructures: Map<String, MonetFileStructure>,
    ) {
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
                        MonetComplexValue(item.nameId, item.toMonetValue(fileStructures))
                    }.toList(),
                )
            } else {
                requireNotNull(entry.resValue) {
                    "scalar ARSC entry 0x${id.toUInt().toString(16)} has no value"
                }.toMonetValue(fileStructures)
            }
            val existing = merged.valuesByQualifiers[qualifiers]
            require(existing == null || existing == value) {
                "conflicting values for 0x${id.toUInt().toString(16)} ($key) qualifiers '$qualifiers' in $apk"
            }
            if (existing == null) merged.valuesByQualifiers[qualifiers] = value
        }
    }

    private fun ValueItem.toMonetValue(fileStructures: Map<String, MonetFileStructure>): MonetResourceValue {
        val valueType = requireNotNull(valueType) { "ARSC value has no value type" }
        if (valueType.isReference) return MonetResourceValue.Reference(data, valueType.name)
        if (valueType == ValueType.STRING) {
            val stringValue = valueAsString
            if (stringValue != null && (stringValue in fileStructures || stringValue.startsWith("res/"))) {
                return MonetResourceValue.File(stringValue, fileStructures[stringValue])
            }
            if (stringValue != null) return MonetResourceValue.Text(stringValue)
        }
        return MonetResourceValue.Literal(
            valueType = valueType.name,
            data = Integer.toUnsignedLong(data),
        )
    }

    private fun com.reandroid.apk.ResFile.fileStructure(): MonetFileStructure {
        val extension = inputSource.extension.uppercase()
        if (!extension.endsWith("PNG")) return MonetFileStructure(extension)
        val format = if (extension.contains(".9.")) "9PNG" else "PNG"
        val header = inputSource.getBytes(4096)
        if (header.size < 26 || header[0].toInt() and 0xff != 0x89 || String(header, 1, 3) != "PNG") {
            return MonetFileStructure(format)
        }
        fun intAt(offset: Int): Int = header[offset].toInt() and 0xff shl 24 or
            (header[offset + 1].toInt() and 0xff shl 16) or
            (header[offset + 2].toInt() and 0xff shl 8) or
            (header[offset + 3].toInt() and 0xff)
        var offset = 8
        var firstDataLength: Int? = null
        var ninePatchLength: Int? = null
        val compressed = ByteArrayOutputStream()
        while (offset + 12 <= header.size) {
            val length = intAt(offset)
            if (length < 0) break
            val type = String(header, offset + 4, 4)
            if (type == "IDAT" && firstDataLength == null) firstDataLength = length
            if (type == "npTc") ninePatchLength = length
            if (offset + 12L + length > header.size) break
            if (type == "IDAT") compressed.write(header, offset + 8, length)
            offset += length + 12
            if (type == "IEND") break
        }
        val pixels = if (intAt(16).toLong() * intAt(20) <= 8192 && header[24].toInt() == 8) {
            pixelStatistics(intAt(16), intAt(20), header[25].toInt() and 0xff, compressed.toByteArray())
        } else null
        return MonetFileStructure(
            format,
            intAt(16),
            intAt(20),
            header[25].toInt() and 0xff,
            firstDataLength,
            ninePatchLength,
            pixels?.sampleSum,
            pixels?.alphaSum,
            pixels?.distinctSamples,
            pixels?.sha256,
        )
    }

    private fun pixelStatistics(
        width: Int,
        height: Int,
        colorType: Int,
        compressed: ByteArray,
    ): PixelStatistics? {
        val bytesPerPixel = when (colorType) {
            0 -> 1
            4 -> 2
            else -> return null
        }
        val stride = width * bytesPerPixel
        val inflated = runCatching {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (inflated.size < (stride + 1) * height) return null
        val previous = ByteArray(stride)
        val current = ByteArray(stride)
        val samples = hashSetOf<Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        var sampleSum = 0L
        var alphaSum = 0L
        var source = 0
        repeat(height) {
            val filter = inflated[source++].toInt() and 0xff
            for (column in 0 until stride) {
                val raw = inflated[source++].toInt() and 0xff
                val left = if (column >= bytesPerPixel) current[column - bytesPerPixel].toInt() and 0xff else 0
                val up = previous[column].toInt() and 0xff
                val upperLeft = if (column >= bytesPerPixel) previous[column - bytesPerPixel].toInt() and 0xff else 0
                current[column] = (raw + when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upperLeft)
                    else -> return null
                }).toByte()
            }
            for (pixel in 0 until width) {
                val sample = current[pixel * bytesPerPixel].toInt() and 0xff
                val alpha = if (bytesPerPixel == 2) current[pixel * bytesPerPixel + 1].toInt() and 0xff else 255
                sampleSum += sample
                alphaSum += alpha
                samples += sample shl 8 or alpha
            }
            digest.update(current)
            current.copyInto(previous)
        }
        return PixelStatistics(
            sampleSum,
            alphaSum,
            samples.size,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val upDistance = kotlin.math.abs(prediction - up)
        val upperLeftDistance = kotlin.math.abs(prediction - upperLeft)
        return if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) left
        else if (upDistance <= upperLeftDistance) up else upperLeft
    }

    private data class PixelStatistics(
        val sampleSum: Long,
        val alphaSum: Long,
        val distinctSamples: Int,
        val sha256: String,
    )

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

    private data class OwnedXml(
        val identity: XmlIdentity,
        val xml: MonetBinaryXml,
    )

    private val MONET_XML_RESOURCE_TYPES = setOf("color", "drawable", "layout")
}
