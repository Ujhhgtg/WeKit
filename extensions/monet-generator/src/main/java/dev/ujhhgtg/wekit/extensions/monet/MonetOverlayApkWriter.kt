package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File

internal object MonetOverlayApkWriter {
    data class ColorTarget(val name: String, val lightId: Int, val nightId: Int? = null)

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
        table.refreshFull()
        apk.refreshTable()
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
    }

    private const val ATTR_PRIORITY = 0x0101001c
    private const val ATTR_TARGET_PACKAGE = 0x01010021
    private const val ATTR_IS_STATIC = 0x0101055a
}
