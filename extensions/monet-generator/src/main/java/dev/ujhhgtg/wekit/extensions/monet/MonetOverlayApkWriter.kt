package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ComplexUtil
import com.reandroid.arsc.coder.UnitDimension
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipEntry

internal object MonetOverlayApkWriter {
    sealed interface ColorValue {
        data class Reference(val id: Int) : ColorValue
        data class Literal(val argb: Int) : ColorValue
    }
    data class ColorTarget(val name: String, val light: ColorValue?, val night: ColorValue?) {
        init { require(light != null || night != null) }
    }
    data class LiteralColorTarget(val name: String, val lightArgb: Int, val nightArgb: Int? = null)
    data class StringTarget(val name: String, val value: String, val qualifiers: String = "")
    data class DrawableTarget(
        val name: String,
        val light: XmlNode,
        val night: XmlNode? = null,
        val type: String = "drawable",
        val lightQualifiers: String = "",
        val nightQualifiers: String = "-night",
    )
    data class XmlNode(
        val name: String,
        val attributes: List<XmlAttribute> = emptyList(),
        val children: List<XmlNode> = emptyList(),
    )
    data class XmlAttribute(val name: String, val id: Int, val value: XmlValue)
    sealed interface XmlValue {
        data class Reference(val id: Int) : XmlValue
        data class NamedReference(val type: kotlin.String, val name: kotlin.String) : XmlValue
        data class Color(val argb: Int) : XmlValue
        data class Dimension(val dp: kotlin.Float) : XmlValue
        data class Integer(val value: Int) : XmlValue
        data class Boolean(val value: kotlin.Boolean) : XmlValue
        data class Float(val value: kotlin.Float) : XmlValue
        data class String(val value: kotlin.String) : XmlValue
    }

    fun createSigned(
        output: File,
        packageName: String,
        sdk: Int,
        versionName: String,
        versionCode: Long,
        colors: Map<String, Int>,
    ) {
        val minSdk = if (sdk >= 34) 34 else 31
        val targetSdk = if (sdk >= 34) 36 else 33
        val unsigned = File(output.parentFile, ".${output.name}.unsigned")
        try {
            create(unsigned, packageName, minSdk, targetSdk, versionName, versionCode, colors)
            MonetApkSigner.sign(unsigned, output, minSdk)
        } finally {
            unsigned.delete()
        }
    }

    fun create(
        output: File,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        versionName: String,
        versionCode: Long,
        colors: Map<String, Int>,
    ) {
        require(versionCode in 0..Int.MAX_VALUE.toLong())
        val apk = ApkModule()
        val manifest = AndroidManifestBlock().apply {
            setPackageName(packageName)
            setVersionName(versionName)
            setVersionCode(versionCode.toInt())
            setMinSdkVersion(minSdk)
            setTargetSdkVersion(targetSdk)
            val overlay = manifestElement.newElement("overlay")
            overlay.createAndroidAttribute("targetPackage", ATTR_TARGET_PACKAGE)
                .setValueAsString("com.tencent.mm")
            overlay.createAndroidAttribute("isStatic", ATTR_IS_STATIC).setValueAsBoolean(true)
            overlay.createAndroidAttribute("priority", ATTR_PRIORITY).apply {
                valueType = ValueType.DEC
                data = 1
            }
            getOrCreateApplicationElement().createAndroidAttribute("hasCode", ATTR_HAS_CODE)
                .setValueAsBoolean(false)
            setExtractNativeLibs(false)
        }
        apk.setManifest(manifest)
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)
        colors.forEach { (name, argb) ->
            val entry = pkg.getOrCreate("", "color", name)
                ?: error("could not create color $name")
            entry.setValueAsRaw(ValueType.COLOR_ARGB8, argb)
        }
        requireNotNull(pkg.getResource("color", colors.keys.first()))
        table.refreshFull()
        require(table.bytes.isNotEmpty())
        apk.refreshTable()
        freezeCanonicalTable(apk, table)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    fun createReferenced(
        output: File,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        versionName: String,
        versionCode: Long,
        priority: Int,
        colors: List<ColorTarget>,
        drawables: List<DrawableTarget> = emptyList(),
        literalColors: List<LiteralColorTarget> = emptyList(),
        strings: List<StringTarget> = emptyList(),
    ) {
        require(versionCode in 0..Int.MAX_VALUE.toLong())
        require(priority >= 0)
        val apk = ApkModule()
        val manifest = AndroidManifestBlock().apply {
            setPackageName(packageName)
            setVersionName(versionName)
            setVersionCode(versionCode.toInt())
            setMinSdkVersion(minSdk)
            setTargetSdkVersion(targetSdk)
            val overlay = manifestElement.newElement("overlay")
            overlay.createAndroidAttribute("targetPackage", ATTR_TARGET_PACKAGE).setValueAsString("com.tencent.mm")
            overlay.createAndroidAttribute("isStatic", ATTR_IS_STATIC).setValueAsBoolean(true)
            overlay.createAndroidAttribute("priority", ATTR_PRIORITY).apply {
                valueType = ValueType.DEC
                data = priority
            }
            getOrCreateApplicationElement().createAndroidAttribute("hasCode", ATTR_HAS_CODE)
                .setValueAsBoolean(false)
            setExtractNativeLibs(false)
        }
        apk.setManifest(manifest)
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)
        val specFlags = mutableMapOf<Pair<String, String>, Int>()
        fun record(type: String, name: String, qualifiers: String) {
            val key = type to name
            specFlags[key] = specFlags.getOrDefault(key, 0) or qualifierFlags(qualifiers)
        }
        colors.forEach { color ->
            color.light?.let {
                pkg.getOrCreate("", "color", color.name)!!.setColorValue(it)
                record("color", color.name, "")
            }
            color.night?.let {
                pkg.getOrCreate("-night", "color", color.name)!!.setColorValue(it)
                record("color", color.name, "-night")
            }
        }
        literalColors.forEach { color ->
            pkg.getOrCreate("", "color", color.name)!!.setValueAsRaw(ValueType.COLOR_ARGB8, color.lightArgb)
            color.nightArgb?.let {
                pkg.getOrCreate("-night", "color", color.name)!!.setValueAsRaw(ValueType.COLOR_ARGB8, it)
                record("color", color.name, "-night")
            }
            record("color", color.name, "")
        }
        strings.forEach { string ->
            pkg.getOrCreate(string.qualifiers, "string", string.name)!!.setValueAsString(string.value)
            record("string", string.name, string.qualifiers)
        }
        drawables.forEach { drawable ->
            pkg.getOrCreate(drawable.lightQualifiers, drawable.type, drawable.name)
            record(drawable.type, drawable.name, drawable.lightQualifiers)
            drawable.night?.let {
                pkg.getOrCreate(drawable.nightQualifiers, drawable.type, drawable.name)
                record(drawable.type, drawable.name, drawable.nightQualifiers)
            }
        }
        drawables.forEach { drawable ->
            addXmlResource(apk, pkg, drawable.type, drawable.lightQualifiers, drawable.name, drawable.light)
            drawable.night?.let {
                addXmlResource(apk, pkg, drawable.type, drawable.nightQualifiers, drawable.name, it)
            }
        }
        table.refreshFull()
        specFlags.forEach { (key, flags) -> markSpecFlags(pkg, key.first, key.second, flags) }
        apk.refreshTable()
        freezeCanonicalTable(apk, table)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    private fun com.reandroid.arsc.value.Entry.setColorValue(value: ColorValue) {
        when (value) {
            is ColorValue.Reference -> setValueAsReference(value.id)
            is ColorValue.Literal -> setValueAsRaw(ValueType.COLOR_ARGB8, value.argb)
        }
    }

    private fun markSpecFlags(pkg: PackageBlock, type: String, name: String, flags: Int) {
        val entryId = requireNotNull(pkg.getResource(type, name)).resourceId and 0xffff
        requireNotNull(pkg.getSpecTypePair(type)).specBlock.getSpecFlag(entryId)
            .setInteger(flags)
    }

    private fun qualifierFlags(qualifiers: String): Int {
        if (qualifiers.isEmpty()) return 0
        val parts = qualifiers.removePrefix("-").split('-')
        var result = 0
        if ("night" in parts) result = result or NATIVE_CONFIG_UI_MODE
        if (parts.any { it == "anydpi" || it == "nodpi" || it.endsWith("dpi") }) {
            result = result or NATIVE_CONFIG_DENSITY
        }
        if (parts.any { it.length > 1 && it[0] == 'v' && it.drop(1).all(Char::isDigit) }) {
            result = result or NATIVE_CONFIG_VERSION
        }
        if (parts.firstOrNull()?.matches(Regex("[a-z]{2,3}")) == true) {
            result = result or NATIVE_CONFIG_LOCALE
        }
        return result
    }

    /**
     * ARSCLib 1.4.0 leaves several aapt2 resource-table fields unset when a table is created
     * from scratch. Android's readers are not required to repair those fields. Freeze a canonical
     * byte source after the final refresh so a later BlockInputSource refresh cannot erase them.
     */
    private fun freezeCanonicalTable(apk: ApkModule, table: TableBlock) {
        val bytes = table.bytes
        val tableStrings = table.stringPool
        if (tableStrings.isEmpty) {
            val offset = table.countUpTo(tableStrings)
            putI32(bytes, offset + 20, tableStrings.headerBlock.headerSize)
        }
        table.listPackages().forEach { pkg ->
            val packageOffset = table.countUpTo(pkg)
            putI32(bytes, packageOffset + 0x110, 0)
            putI32(bytes, packageOffset + 0x118, 0)
            pkg.listSpecTypePairs().forEach { pair ->
                val specOffset = table.countUpTo(pair.specBlock)
                putU16(bytes, specOffset + 10, pair.countTypeBlocks())
            }
        }

        apk.removeInputSource(TableBlock.FILE_NAME)
        apk.add(ByteInputSource(bytes, TableBlock.FILE_NAME).apply {
            method = ZipEntry.STORED
            sort = 1
        })
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        putU16(bytes, offset, value)
        putU16(bytes, offset + 2, value ushr 16)
    }

    private fun addXmlResource(
        apk: ApkModule,
        pkg: PackageBlock,
        type: String,
        qualifiers: String,
        name: String,
        node: XmlNode,
    ) {
        val path = "res/$type${qualifiers}/${name}.xml"
        pkg.getOrCreate(qualifiers, type, name)!!.setValueAsString(path)
        val document = ResXmlDocument().apply { packageBlock = pkg }
        document.newElement(node.name).write(node, pkg)
        document.refreshFull()
        apk.add(BlockInputSource(path, document))
    }

    private fun ResXmlElement.write(node: XmlNode, pkg: PackageBlock) {
        node.attributes.forEach { attribute ->
            createAndroidAttribute(attribute.name, attribute.id).apply {
                when (val value = attribute.value) {
                    is XmlValue.Reference -> {
                        valueType = ValueType.REFERENCE
                        data = value.id
                    }
                    is XmlValue.NamedReference -> {
                        valueType = ValueType.REFERENCE
                        data = requireNotNull(pkg.getResource(value.type, value.name)).resourceId
                    }
                    is XmlValue.Color -> {
                        valueType = ValueType.COLOR_ARGB8
                        data = value.argb
                    }
                    is XmlValue.Dimension -> {
                        valueType = ValueType.DIMENSION
                        data = ComplexUtil.encodeComplex(value.dp, UnitDimension.DP)
                    }
                    is XmlValue.Integer -> {
                        valueType = ValueType.DEC
                        data = value.value
                    }
                    is XmlValue.Boolean -> setValueAsBoolean(value.value)
                    is XmlValue.Float -> {
                        valueType = ValueType.FLOAT
                        data = java.lang.Float.floatToIntBits(value.value)
                    }
                    is XmlValue.String -> setValueAsString(value.value)
                }
            }
        }
        node.children.forEach { child -> newElement(child.name).write(child, pkg) }
    }

    private const val ATTR_PRIORITY = 0x0101001c
    private const val ATTR_HAS_CODE = 0x0101000c
    private const val ATTR_TARGET_PACKAGE = 0x01010021
    private const val ATTR_IS_STATIC = 0x0101055a
    private const val NATIVE_CONFIG_LOCALE = 0x00000004
    private const val NATIVE_CONFIG_DENSITY = 0x00000100
    private const val NATIVE_CONFIG_VERSION = 0x00000400
    private const val NATIVE_CONFIG_UI_MODE = 0x00001000
}
