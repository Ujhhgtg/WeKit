package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.apk.ApkModule
import com.reandroid.archive.BlockInputSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ComplexUtil
import com.reandroid.arsc.coder.UnitDimension
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipEntry

/**
 * Writes the runtime resource container loaded through `ResourcesProvider.loadFromApk`.
 *
 * Unlike an RRO there is no idmap: the table joins WeChat's package group by package ID, so every
 * entry keeps WeChat's package/type/entry indices and WeChat's type/key names. A loader only wins a
 * lookup when its config is equal to or better than WeChat's best match, so each target is written
 * for every config WeChat itself declares for that entry (see [configsFor]).
 */
object MonetRuntimePackageWriter {
    sealed interface ColorValue {
        data class Reference(val id: Int) : ColorValue

        data class Literal(val argb: Int) : ColorValue
    }

    data class ColorTarget(
        val binding: MonetBinding,
        val light: ColorValue?,
        val night: ColorValue?,
    ) {
        init {
            require(light != null || night != null)
        }
    }

    data class DrawableTarget(
        val binding: MonetBinding,
        val light: XmlNode,
        val night: XmlNode? = null,
    )

    data class XmlNode(
        val name: String,
        val attributes: List<XmlAttribute> = emptyList(),
        val children: List<XmlNode> = emptyList(),
    )

    data class XmlAttribute(val name: String, val id: Int, val value: XmlValue)

    sealed interface XmlValue {
        data class Reference(val id: Int) : XmlValue

        data class Color(val argb: Int) : XmlValue

        data class Dimension(val dp: kotlin.Float) : XmlValue

        data class Integer(val value: Int) : XmlValue

        data class Boolean(val value: kotlin.Boolean) : XmlValue

        data class Float(val value: kotlin.Float) : XmlValue

        data class String(val value: kotlin.String) : XmlValue
    }

    /**
     * @param pathPrefix unique per build; `ResourcesImpl` caches parsed XML by (path, cookie), and
     *   a swapped provider reuses the same cookie slot.
     */
    fun write(
        output: File,
        typeNames: List<String>,
        colors: List<ColorTarget>,
        drawables: List<DrawableTarget>,
        pathPrefix: String,
    ) {
        require(typeNames.isNotEmpty()) { "WeChat type pool is empty" }
        val targets = colors.map { it.binding } + drawables.map { it.binding }
        require(targets.distinctBy(MonetBinding::id).size == targets.size) {
            "duplicate Monet target"
        }
        val apk = ApkModule()
        val table = TableBlock()
        apk.tableBlock = table
        val pkg = table.newPackage(PACKAGE_ID, PACKAGE_NAME)
        typeNames.forEachIndexed { index, name -> pkg.typeStringPool.getOrCreate(index + 1, name) }
        val specFlags = mutableMapOf<MonetBinding, Int>()

        colors.forEach { color ->
            configsFor(color.binding, color.night != null).forEach { (config, night) ->
                // A missing side keeps WeChat's own value: no loader entry means WeChat's config
                // wins.
                val value = (if (night) color.night else color.light) ?: return@forEach
                pkg.entry(color.binding, config) { setColorValue(value) }
                specFlags.record(color.binding, config)
            }
        }
        drawables.forEach { drawable ->
            configsFor(drawable.binding, drawable.night != null).forEach { (config, night) ->
                val node = if (night) drawable.night ?: return@forEach else drawable.light
                val path =
                    "res/$pathPrefix/${drawable.binding.type}${config.qualifiers}/${drawable.binding.name}.xml"
                pkg.entry(drawable.binding, config) { valueAsString = path }
                val document = ResXmlDocument().apply { packageBlock = pkg }
                document.newElement(node.name).write(node)
                document.refreshFull()
                apk.add(BlockInputSource(path, document))
                specFlags.record(drawable.binding, config)
            }
        }

        table.refreshFull()
        specFlags.forEach { (binding, flags) ->
            requireNotNull(pkg.getSpecTypePair(binding.typeId))
                .specBlock
                .getSpecFlag(binding.entryId)
                .integer = binding.specFlags or flags
        }
        apk.refreshTable()
        freezeCanonicalTable(apk, table)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    /**
     * WeChat's configs for [binding], each paired with whether it takes the night value. When the
     * target has a night value but WeChat never declares a night config, a night twin of every
     * config is added: uiMode ranks above density/version in `ResTable_config::isBetterThan`, so
     * the twins win as better matches instead of relying on equal-config overlay. A config whose
     * side has no value is skipped by the caller, leaving WeChat's value in effect for that mode.
     */
    fun configsFor(binding: MonetBinding, hasNight: Boolean): List<Pair<ResConfig, Boolean>> {
        val declared = binding.qualifiers.ifEmpty { listOf("") }.map(ResConfig::parse)
        val twins =
            if (hasNight && declared.none { it.isNight }) {
                declared.map { config ->
                    ResConfig().apply {
                        copyFrom(config)
                        uiModeNight = ResConfig.UiModeNight.NIGHT
                    }
                }
            } else {
                emptyList()
            }
        return (declared + twins).distinctBy { it.qualifiers }.map { it to it.isNight }
    }

    private val ResConfig.isNight: Boolean
        get() = uiModeNight == ResConfig.UiModeNight.NIGHT

    private val MonetBinding.typeId: Int
        get() = id ushr 16 and 0xff

    private val MonetBinding.entryId: Int
        get() = id and 0xffff

    /** Names the entry after [setValue]: assigning a value rebuilds the entry's table record. */
    private fun PackageBlock.entry(
        binding: MonetBinding,
        config: ResConfig,
        setValue: Entry.() -> Unit,
    ) {
        require(binding.id ushr 24 == PACKAGE_ID) {
            "not a WeChat resource: ${binding.id.toUInt().toString(16)}"
        }
        require(typeNameOf(binding.typeId) == binding.type) {
            "type ${binding.typeId} is ${typeNameOf(binding.typeId)}, expected ${binding.type}"
        }
        val entry =
            getOrCreateSpecTypePair(binding.typeId, binding.type)
                .getOrCreateEntry(binding.entryId.toShort(), config)
        entry.setValue()
        entry.name = binding.name
    }

    private fun MutableMap<MonetBinding, Int>.record(binding: MonetBinding, config: ResConfig) {
        this[binding] = getOrDefault(binding, 0) or qualifierFlags(config.qualifiers)
    }

    private fun Entry.setColorValue(value: ColorValue) {
        when (value) {
            is ColorValue.Reference -> setValueAsReference(value.id)
            is ColorValue.Literal -> setValueAsRaw(ValueType.COLOR_ARGB8, value.argb)
        }
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
     * ARSCLib 1.4.0 leaves several aapt2 resource-table fields unset when a table is created from
     * scratch. Android's readers are not required to repair those fields. Freeze a canonical byte
     * source after the final refresh so a later BlockInputSource refresh cannot erase them.
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
        apk.add(
            ByteInputSource(bytes, TableBlock.FILE_NAME).apply {
                method = ZipEntry.STORED
                sort = 1
            },
        )
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        putU16(bytes, offset, value)
        putU16(bytes, offset + 2, value ushr 16)
    }

    private fun ResXmlElement.write(node: XmlNode) {
        node.attributes.forEach { attribute ->
            createAndroidAttribute(attribute.name, attribute.id).apply {
                when (val value = attribute.value) {
                    is XmlValue.Reference -> {
                        valueType = ValueType.REFERENCE
                        data = value.id
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
                    is XmlValue.Boolean -> valueAsBoolean = value.value
                    is XmlValue.Float -> {
                        valueType = ValueType.FLOAT
                        data = java.lang.Float.floatToIntBits(value.value)
                    }
                    is XmlValue.String -> valueAsString = value.value
                }
            }
        }
        node.children.forEach { child -> newElement(child.name).write(child) }
    }

    private const val PACKAGE_ID = 0x7f
    private const val PACKAGE_NAME = "com.tencent.mm"
    private const val NATIVE_CONFIG_LOCALE = 0x00000004
    private const val NATIVE_CONFIG_DENSITY = 0x00000100
    private const val NATIVE_CONFIG_VERSION = 0x00000400
    private const val NATIVE_CONFIG_UI_MODE = 0x00001000
}
