package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.MonetApkResourceGraphLoader
import dev.ujhhgtg.wekit.extensions.monet.MonetStructureMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.luckypray.dexkit.DexKitBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory

@EnabledIfSystemProperty(named = "wekit.monetCorpus", matches = "true")
class MonetMatcherCorpusTest {
    @Test
    fun `production matcher resolves the complete local APK corpus with live Dex evidence`() {
        System.load(File("../.wekit/dex-test/native/2.2.0/x86_64/cmake/libdexkit.so").canonicalPath)
        val samples = listOf("8065", "8067", "8069", "8074", "8076", "8077", "8069_3020_play").map {
            File("/home/ujhhgtg/coding/wechat_$it.apk")
        } + File("/home/ujhhgtg/Downloads/com.tencent.mm_8.0.72-3084_1arch_7dpi_24lang_2feat_17c51f333f2c0751329ed31584832928_apkmirror.com.apks")

        samples.forEach { sample ->
            val extracted = if (sample.extension == "apks") extractResourceApks(sample) else null
            try {
                val graph = MonetApkResourceGraphLoader.load(extracted?.second ?: listOf(sample), "com.tencent.mm")
                DexKitBridge.create(dexBytes(sample).toTypedArray()).use { bridge ->
                    val resolved = MonetStructureMatcher.resolveAll(graph) { candidates ->
                        MonetDexEvidenceCollector.collect(bridge, candidates)
                    }
                    assertEquals(MonetStructureMatcher.roleIds, resolved.keys, sample.name)
                    EXPECTED_DEX_TARGETS.getValue(sample.name).forEach { (role, name) ->
                        assertEquals(name, resolved.getValue(role).key.name, "${sample.name}: $role")
                    }
                }
            } finally {
                extracted?.first?.deleteRecursively()
            }
        }
    }

    private fun extractResourceApks(apks: File): Pair<File, List<File>> {
        val dir = createTempDirectory("monet-apks").toFile()
        val result = mutableListOf<File>()
        ZipFile(apks).use { outer ->
            outer.entries().asSequence().filter { it.name.endsWith(".apk") }.forEach { entry ->
                val bytes = outer.getInputStream(entry).readBytes()
                if (ZipInputStream(ByteArrayInputStream(bytes)).use { nested ->
                        generateSequence(nested::getNextEntry).any { it.name == "resources.arsc" }
                    }
                ) {
                    result += File(dir, entry.name).also { it.writeBytes(bytes) }
                }
            }
        }
        return dir to result.sortedBy { if (it.name == "base.apk") "" else it.name }
    }

    private fun dexBytes(apk: File): List<ByteArray> = if (apk.extension == "apks") {
        ZipFile(apk).use { outer ->
            outer.entries().asSequence().filter { it.name.endsWith(".apk") }.sortedBy { it.name }.flatMap { entry ->
                nestedDex(outer.getInputStream(entry).readBytes()).asSequence()
            }.toList()
        }
    } else {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(DEX_NAME) }.sortedBy { it.name }
                .map { zip.getInputStream(it).readBytes() }.toList()
        }
    }

    private fun nestedDex(apk: ByteArray): List<ByteArray> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.matches(DEX_NAME)) result += entry.name to zip.readBytes()
            }
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    private companion object {
        val DEX_NAME = Regex("classes(\\d*)?\\.dex")
        val DEX_ROLES = listOf(
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-26",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-27",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-42",
            "theme.color.unknown--10ffffff.slot-06",
            "theme.color.unknown--system-surface-dark.slot-02",
        )
        val EXPECTED_DEX_TARGETS = mapOf(
            "wechat_8065.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8067.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8069.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8074.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8076.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8077.apk" to listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8069_3020_play.apk" to listOf("adl", "af0", "n0", "a_z", "ni"),
            "com.tencent.mm_8.0.72-3084_1arch_7dpi_24lang_2feat_17c51f333f2c0751329ed31584832928_apkmirror.com.apks" to
                listOf("adr", "af6", "n0", "aa4", "ni"),
        ).mapValues { (_, names) -> DEX_ROLES.zip(names).toMap() }
    }
}
