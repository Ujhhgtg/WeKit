package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ComplexUtil
import com.reandroid.arsc.coder.UnitDimension
import com.reandroid.arsc.value.ValueType
import com.reandroid.archive.BlockInputSource
import java.io.File

internal object MonetOverlayApkWriter {
    data class ColorTarget(val name: String, val lightId: Int, val nightId: Int? = null)
    data class LiteralColorTarget(val name: String, val lightArgb: Int, val nightArgb: Int? = null)
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
        colors: Map<String, Int>,
    ) {
        val minSdk = if (sdk >= 34) 34 else 31
        val targetSdk = if (sdk >= 34) 36 else 33
        val unsigned = File(output.parentFile, ".${output.name}.unsigned")
        try {
            create(unsigned, packageName, minSdk, targetSdk, colors)
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
        colors: Map<String, Int>,
    ) {
        val apk = ApkModule()
        val manifest = AndroidManifestBlock.empty().apply {
            setPackageName(packageName)
            setMinSdkVersion(minSdk)
            setTargetSdkVersion(targetSdk)
            val overlay = manifestElement.newElement("overlay")
            overlay.createAndroidAttribute("targetPackage", ATTR_TARGET_PACKAGE)
                .setValueAsString("com.tencent.mm")
            overlay.createAndroidAttribute("isStatic", ATTR_IS_STATIC).setValueAsBoolean(true)
            overlay.createAndroidAttribute("priority", ATTR_PRIORITY).apply {
                valueType = com.reandroid.arsc.value.ValueType.DEC
                data = 1
            }
        }
        apk.setManifest(manifest)
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)
        colors.forEach { (name, argb) ->
            val entry = pkg.getOrCreate("", "color", name)
                ?: error("could not create color $name")
            entry.setValueAsRaw(com.reandroid.arsc.value.ValueType.COLOR_ARGB8, argb)
        }
        requireNotNull(pkg.getResource("color", colors.keys.first()))
        table.refreshFull()
        require(table.bytes.isNotEmpty())
        apk.refreshTable()
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    fun createReferenced(
        output: File,
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        colors: List<ColorTarget>,
        drawables: List<DrawableTarget> = emptyList(),
        literalColors: List<LiteralColorTarget> = emptyList(),
    ) {
        val apk = ApkModule()
        val manifest = AndroidManifestBlock.empty().apply {
            setPackageName(packageName)
            setMinSdkVersion(minSdk)
            setTargetSdkVersion(targetSdk)
            val overlay = manifestElement.newElement("overlay")
            overlay.createAndroidAttribute("targetPackage", ATTR_TARGET_PACKAGE).setValueAsString("com.tencent.mm")
            overlay.createAndroidAttribute("isStatic", ATTR_IS_STATIC).setValueAsBoolean(true)
            overlay.createAndroidAttribute("priority", ATTR_PRIORITY).apply {
                valueType = com.reandroid.arsc.value.ValueType.DEC
                data = 1
            }
        }
        apk.setManifest(manifest)
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)
        colors.forEach { color ->
            pkg.getOrCreate("", "color", color.name)!!.setValueAsReference(color.lightId)
            color.nightId?.let { pkg.getOrCreate("-night", "color", color.name)!!.setValueAsReference(it) }
        }
        literalColors.forEach { color ->
            pkg.getOrCreate("", "color", color.name)!!.setValueAsRaw(ValueType.COLOR_ARGB8, color.lightArgb)
            color.nightArgb?.let {
                pkg.getOrCreate("-night", "color", color.name)!!.setValueAsRaw(ValueType.COLOR_ARGB8, it)
            }
        }
        drawables.forEach { drawable ->
            pkg.getOrCreate(drawable.lightQualifiers, drawable.type, drawable.name)
            drawable.night?.let { pkg.getOrCreate(drawable.nightQualifiers, drawable.type, drawable.name) }
        }
        drawables.forEach { drawable ->
            addXmlResource(apk, pkg, drawable.type, drawable.lightQualifiers, drawable.name, drawable.light)
            drawable.night?.let {
                addXmlResource(apk, pkg, drawable.type, drawable.nightQualifiers, drawable.name, it)
            }
        }
        table.refreshFull()
        apk.refreshTable()
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
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
        val document = ResXmlDocument().apply { setPackageBlock(pkg) }
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
    private const val ATTR_TARGET_PACKAGE = 0x01010021
    private const val ATTR_IS_STATIC = 0x0101055a
}
