package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MonetApkResourceGraphLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loader reads colors drawables and xml references without framework apk`() {
        val graph = MonetApkResourceGraphLoader.load(
            listOf(File("../../app/embedded/monet/templates/template_base_api31.apk")),
            TARGET_PACKAGE,
        )

        assertNotNull(graph.node(MonetResourceKey("color", "Brand")))
        assertTrue(graph.nodes("drawable").isNotEmpty())
        assertTrue(graph.xmlOwners().isNotEmpty())
    }

    @Test
    fun `loader persists stable xml shapes and deduplicates identical apk inputs`() {
        val single = MonetApkResourceGraphLoader.load(listOf(template), TARGET_PACKAGE)
        val duplicate = MonetApkResourceGraphLoader.load(listOf(template, template), TARGET_PACKAGE)
        val splashId = single.node(MonetResourceKey("drawable", "dhq"))!!.id

        assertTrue(single.xmlShapes(splashId).isNotEmpty())
        assertEquals(single.xmlShapes(splashId), duplicate.xmlShapes(splashId))
    }

    @Test
    fun `loader reads binary xml from obfuscated path using arsc owner type`() {
        val obfuscated = writeFixture("obfuscated-path.apk") { module ->
            module.listResFiles().single { resFile ->
                resFile.asSequence().any { it.name == "dhq" }
            }.filePath = "res/i/dhq.xml"
        }

        val graph = MonetApkResourceGraphLoader.load(listOf(obfuscated), TARGET_PACKAGE)
        val logoId = graph.node(MonetResourceKey("drawable", "dhq"))!!.id
        val iconId = graph.node(MonetResourceKey("drawable", "icon"))!!.id

        assertTrue(graph.xmlShapes(logoId).isNotEmpty())
        assertTrue(iconId in graph.outgoing(logoId))
    }

    @Test
    fun `loader rejects conflicting arsc values across apk inputs`() {
        val conflicting = writeFixture("arsc-conflict.apk") { module ->
            targetPackage(module).getEntries("color", "Brand").asSequence().first()
                .setValueAsRaw(ValueType.COLOR_ARGB8, 0xff123456.toInt())
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            MonetApkResourceGraphLoader.load(listOf(template, conflicting), TARGET_PACKAGE)
        }

        assertTrue(error.message!!.contains("conflicting values"))
    }

    @Test
    fun `loader rejects conflicting binary xml across apk inputs`() {
        val conflicting = writeFixture("xml-conflict.apk") { module ->
            val (resFile, document, attribute) = module.listResFiles().asSequence()
                .filter { it.filePath.startsWith("res/drawable") && it.filePath.endsWith(".xml") }
                .filter { it.isBinaryXml }
                .mapNotNull { resFile ->
                    val document = module.loadResXmlDocument(resFile.inputSource)
                    val attribute = document.recursive(ResXmlElement::class.java).asSequence()
                        .flatMap { it.attributes.asSequence() }
                        .firstOrNull { it.valueType == ValueType.DEC }
                    attribute?.let { Triple(resFile, document, it) }
                }
                .first()
            attribute.valueType = ValueType.HEX
            module.add(BlockInputSource(resFile.inputSource, document))
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            MonetApkResourceGraphLoader.load(listOf(template, conflicting), TARGET_PACKAGE)
        }

        assertTrue(error.message!!.contains("conflicting binary XML"))
    }

    @Test
    fun `loader retains complex bag parent keys and typed values`() {
        val complexFixture = writeFixture("complex.apk") { module ->
            val entry = targetPackage(module).getEntries("color", "Brand").asSequence().first()
            entry.ensureComplex(true)
            entry.resTableMapEntry.apply {
                parentId = COMPLEX_PARENT_ID
                value.createNext().apply {
                    nameId = COMPLEX_LITERAL_KEY
                    setTypeAndData(ValueType.DEC, 7)
                }
                value.createNext().apply {
                    nameId = COMPLEX_REFERENCE_KEY
                    setTypeAndData(ValueType.ATTRIBUTE, COMPLEX_REFERENCE_ID)
                }
            }
        }

        val graph = MonetApkResourceGraphLoader.load(listOf(complexFixture), TARGET_PACKAGE)

        assertEquals(
            MonetResourceValue.Complex(
                parentId = COMPLEX_PARENT_ID,
                items = listOf(
                    MonetComplexValue(
                        COMPLEX_LITERAL_KEY,
                        MonetResourceValue.Literal("DEC", 7),
                    ),
                    MonetComplexValue(
                        COMPLEX_REFERENCE_KEY,
                        MonetResourceValue.Reference(COMPLEX_REFERENCE_ID, "ATTRIBUTE"),
                    ),
                ),
            ),
            graph.node(MonetResourceKey("color", "Brand"))!!.values
                .single { it.value is MonetResourceValue.Complex }
                .value,
        )
    }

    private fun writeFixture(name: String, mutate: (ApkModule) -> Unit): File {
        val output = File(tempDir, name)
        ApkModule.loadApkFile(template).apply { setLoadDefaultFramework(false) }.use { module ->
            mutate(module)
            module.apkSignatureBlock = null
            module.writeApk(output)
        }
        return output
    }

    private fun targetPackage(module: ApkModule): PackageBlock =
        module.tableBlock.listPackages().single { it.name == TARGET_PACKAGE }

    private companion object {
        const val TARGET_PACKAGE = "monet.com.tencent.mm"
        const val COMPLEX_PARENT_ID = 0x0106000d
        const val COMPLEX_LITERAL_KEY = 0x01010000
        const val COMPLEX_REFERENCE_KEY = 0x01010001
        const val COMPLEX_REFERENCE_ID = 0x0106000e

        val template = File("../../app/embedded/monet/templates/template_base_api31.apk")
    }
}
