package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.MonetApkResourceGraphLoader
import dev.ujhhgtg.wekit.extensions.monet.MonetStructureMatcher
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceGraph
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceNode
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `directly compare reference color targets to domestic resources`() {
        val play = extractApks(File("/home/ujhhgtg/coding/wechat_8072_3084.apks"))
        val referenceApk = File.createTempFile("monet-reference-", ".apk").apply {
            writeBytes(ZipFile("/home/ujhhgtg/Downloads/WeChatMonet_Pro_v26S4.zip").use { zip ->
                zip.getInputStream(zip.getEntry("files/MonetWeChat.apk")).readBytes()
            })
        }
        val mismatches = mutableListOf<String>()
        try {
            val source = MonetApkResourceGraphLoader.load(play.second, "com.tencent.mm")
            val referenceOverlay = MonetApkResourceGraphLoader.load(listOf(referenceApk), "monet.com.tencent.mm")
            val playStructural = auditResources(File("/home/ujhhgtg/coding/wechat_8072_3084.apks"), source)
            mapOf(
                "chat.transfer.incoming.expired" to "c2c_chatfrom_remittance_expired_bg",
                "chat.transfer.incoming.received" to "z1",
                "chat.transfer.outgoing.expired" to "c2c_chatto_remittance_expired_bg",
                "chat.transfer.outgoing.received" to "zc",
                "theme.color.unknown--10ffffff.slot-06" to "aa4",
                "theme.color.unknown--system-surface-dark.slot-02" to "ni",
            ).forEach { (role, name) ->
                assertEquals(name, playStructural.getValue(role).singleOrNull()?.key?.name, "Play $role candidates=${playStructural.getValue(role).map { it.key.name }}")
            }
            fun comparePalette(sampleName: String, target: MonetResourceGraph, structural: Map<String, List<MonetResourceNode>>) {
                val expectedAbsent = EXPECTED_ABSENT.getValue(sampleName)
                val before = mismatches.size
                for (role in MonetStructureMatcher.roleIds.filter { it.startsWith("theme.color.") }) {
                    val reference = playStructural.getValue(role).single()
                    assertTrue(referenceOverlay.node(reference.key) != null, "reference APK missing ${reference.key}")
                    val selected = structural.getValue(role).singleOrNull()
                    if (selected == null) {
                        assertTrue(role in expectedAbsent, "$sampleName unexpectedly omitted $role")
                        continue
                    }
                    if (role !in SOURCE_VERIFIED_ROLES &&
                        MonetStructureMatcher.candidates(reference, source, target).none { it.id == selected.id }
                    ) mismatches += "$sampleName $role selected ${selected.key.name} but does not match Play XML/value feature"
                }
                println("MONET_PALETTE_RESULT $sampleName mismatches=${mismatches.size - before}")
            }
            DOMESTIC_SLOT_57.forEach { (version, expectedSlot57) ->
                val target = MonetApkResourceGraphLoader.load(
                    listOf(File(System.getProperty("wekit.monetTarget.$version") ?: "/home/ujhhgtg/coding/wechat_$version.apk")),
                    "com.tencent.mm",
                )
                val structural = auditResources(
                    File(System.getProperty("wekit.monetTarget.$version") ?: "/home/ujhhgtg/coding/wechat_$version.apk"),
                    target,
                )
                val resolved = structural.filterValues { it.isNotEmpty() }.mapValues { it.value.single() }
                assertEquals(MonetStructureMatcher.roleIds, structural.keys, version)
                val expectedAbsent = EXPECTED_ABSENT.getValue("wechat_$version.apk")
                assertEquals(expectedAbsent, structural.filterValues { it.isEmpty() }.keys, version)
                assertEquals(MonetStructureMatcher.roleIds - expectedAbsent, resolved.keys, version)
                assertTrue(resolved.values.none { it.key.name in HOST_FOREGROUND_COLORS }, version)
                assertTrue(
                    structural.filterKeys { it !in expectedAbsent }
                        .values.all { it.size == 1 },
                    "$version unresolved: ${structural.filterValues { it.size != 1 }.mapValues { it.value.map { node -> node.key.name } }}",
                )
                assertEquals(expectedSlot57, structural.getValue(SURFACE_CONTAINER_SLOT_57).singleOrNull()?.key?.name, version)
                assertEquals(DOMESTIC_SEARCH_BAR.getValue(version), structural.getValue(SEARCH_BAR_BACKGROUND).singleOrNull()?.key?.name, version)
                assertEquals("ga", structural.getValue(THREE_STATE_STROKE).single().key.name, version)
                assertEquals(DOMESTIC_FINDER_LIVE_TAB.getValue(version), structural.getValue(FINDER_LIVE_TAB).single().key.name, version)
                assertEquals("tt", structural.getValue(DELETE_ACTION_COLOR).single().key.name, version)
                assertEquals("dp", structural.getValue(APP_BRAND_PAGE_BACKGROUND).single().key.name, version)
                assertEquals("up", structural.getValue(SURFACE_CONTAINER_SLOT_27).single().key.name, version)
                assertEquals("ob", structural.getValue("theme.color.system-surface-container-dark--system-surface-container-dark.slot-04").single().key.name, version)
                assertEquals(DOMESTIC_VIP_FOREGROUND.getValue(version), structural.getValue("theme.color.system-surface-container-dark--system-surface-container-dark.slot-10").single().key.name, version)
                assertEquals(DOMESTIC_FEEDBACK_BACKGROUND.getValue(version), structural.getValue(SURFACE_CONTAINER_SLOT_56).singleOrNull()?.key?.name, version)
                assertEquals("rh", structural.getValue("theme.color.unknown--10ffffff.slot-06").single().key.name, version)
                assertEquals("e2", structural.getValue("theme.color.unknown--system-surface-dark.slot-02").single().key.name, version)
                assertEquals("c2c_chatfrom_remittance_expired_bg", structural.getValue("chat.transfer.incoming.expired").single().key.name, version)
                assertEquals("k6", structural.getValue("chat.transfer.incoming.received").single().key.name, version)
                assertEquals("c2c_chatto_remittance_expired_bg", structural.getValue("chat.transfer.outgoing.expired").single().key.name, version)
                assertEquals("k9", structural.getValue("chat.transfer.outgoing.received").single().key.name, version)
                comparePalette("wechat_$version.apk", target, structural)
            }
            val olderPlay = File("/home/ujhhgtg/coding/wechat_8069_3020_play.apk")
            val olderPlayGraph = MonetApkResourceGraphLoader.load(listOf(olderPlay), "com.tencent.mm")
            comparePalette(olderPlay.name, olderPlayGraph, auditResources(olderPlay, olderPlayGraph))
            val newerPlay = extractApks(PLAY_3085_APKS)
            try {
                val newerPlayGraph = MonetApkResourceGraphLoader.load(newerPlay.second, "com.tencent.mm")
                comparePalette(PLAY_3085_FILE_NAME, newerPlayGraph, auditResources(PLAY_3085_APKS, newerPlayGraph))
            } finally {
                newerPlay.first.deleteRecursively()
            }
            assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
        } finally {
            play.first.deleteRecursively()
            referenceApk.delete()
        }
    }

    @Test
    fun `production matcher resolves the complete local APK corpus with live Dex evidence`() {
        System.load(File("../.wekit/dex-test/native/2.2.0/x86_64/cmake/libdexkit.so").canonicalPath)
        val samples = listOf("8065", "8067", "8069", "8074", "8076", "8077", "8078", "8069_3020_play").map {
            File("/home/ujhhgtg/coding/wechat_$it.apk")
        } + listOf(File("/home/ujhhgtg/coding/wechat_8072_3084.apks"), PLAY_3085_APKS)
        val failures = mutableListOf<String>()

        samples.forEach { sample ->
            val extracted = if (sample.extension == "apks") extractApks(sample) else null
            try {
                val graph = MonetApkResourceGraphLoader.load(extracted?.second ?: listOf(sample), "com.tencent.mm")
                DexKitBridge.create(dexBytes(sample).toTypedArray()).use { bridge ->
                    val dexProvider = MonetDexEvidenceProvider { candidates ->
                        MonetDexEvidenceCollector.collect(bridge, candidates)
                    }
                    val audit = MonetStructureMatcher.audit(graph, dexProvider)
                    assertEquals(MonetStructureMatcher.roleIds, audit.keys, sample.name)
                    val expectedAbsent = EXPECTED_ABSENT.getValue(sample.name)
                    assertEquals(expectedAbsent, audit.filterValues { it.isEmpty() }.keys, sample.name)
                    val resolved = MonetStructureMatcher.resolveAll(graph, dexProvider)
                    assertEquals(MonetStructureMatcher.roleIds - expectedAbsent, resolved.keys, sample.name)
                    audit.filter { (role, candidates) -> role !in expectedAbsent && candidates.size != 1 }.forEach { (role, candidates) ->
                        failures += "${sample.name}: $role -> ${candidates.map { it.key }}"
                    }
                    audit.filterValues { it.size == 1 }.entries.groupBy { it.value.single().id }
                        .filterValues { it.size > 1 }.values.forEach { collision ->
                            failures += "${sample.name}: duplicate ${collision.map { it.key }} -> ${collision.first().value.single().key}"
                        }
                    EXPECTED_TARGETS.getValue(sample.name).forEach { (role, name) ->
                        audit.getValue(role).singleOrNull()?.let { resolved ->
                            if (resolved.key.name != name) failures += "${sample.name}: $role expected $name, got ${resolved.key.name}"
                        }
                    }
                    val expectedTransfer = EXPECTED_TRANSFER_TARGETS.getValue(sample.name)
                    val actualIncoming = INCOMING_TRANSFER_ROLES.mapNotNull { audit.getValue(it).singleOrNull()?.key?.name }.toSet()
                    val actualOutgoing = OUTGOING_TRANSFER_ROLES.mapNotNull { audit.getValue(it).singleOrNull()?.key?.name }.toSet()
                    if (actualIncoming != expectedTransfer.first) failures += "${sample.name}: incoming transfer $actualIncoming"
                    if (actualOutgoing != expectedTransfer.second) failures += "${sample.name}: outgoing transfer $actualOutgoing"
                    println("MONET_APK_RESULT ${sample.name} resolved=${resolved.size} expectedAbsent=${expectedAbsent.sorted()}")
                }
            } finally {
                extracted?.first?.deleteRecursively()
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun auditResources(apk: File, graph: MonetResourceGraph): Map<String, List<MonetResourceNode>> {
        System.load(File("../.wekit/dex-test/native/2.2.0/x86_64/cmake/libdexkit.so").canonicalPath)
        return DexKitBridge.create(dexBytes(apk).toTypedArray()).use { bridge ->
            MonetStructureMatcher.audit(graph) { candidates -> MonetDexEvidenceCollector.collect(bridge, candidates) }
        }
    }

    @Test
    fun `installed Play split subset resolves every role including code-only splits`() {
        val extracted = extractApks(PLAY_3085_APKS)
        try {
            // The actual sourceDir/splitSourceDirs combination on the reporting device.
            val installedNames = setOf(
                "base.apk", "split_config.arm64_v8a.apk", "split_config.xxxhdpi.apk",
                "split_config.zh.apk", "split_delivery.apk", "split_delivery.config.arm64_v8a.apk",
            )
            val installedApks = extracted.second.filter { it.name in installedNames }
            assertEquals(installedNames, installedApks.map { it.name }.toSet())
            val graph = MonetApkResourceGraphLoader.load(installedApks, "com.tencent.mm")
            System.load(File("../.wekit/dex-test/native/2.2.0/x86_64/cmake/libdexkit.so").canonicalPath)
            DexKitBridge.create(installedApks.flatMap(::dexBytes).toTypedArray()).use { bridge ->
                val resolved = MonetStructureMatcher.resolveAll(
                    graph,
                    MonetDexEvidenceProvider { candidates -> MonetDexEvidenceCollector.collect(bridge, candidates) },
                )
                assertEquals(MonetStructureMatcher.roleIds, resolved.keys)
                println("MONET_INSTALLED_SPLITS_RESULT version=3085 apks=${installedApks.size} resolved=${resolved.size}")
            }
        } finally {
            extracted.first.deleteRecursively()
        }
    }

    private fun extractApks(apks: File): Pair<File, List<File>> {
        val dir = createTempDirectory("monet-apks").toFile()
        val result = mutableListOf<File>()
        ZipFile(apks).use { outer ->
            outer.entries().asSequence().filter { it.name.endsWith(".apk") }.forEach { entry ->
                val bytes = outer.getInputStream(entry).readBytes()
                // Keep ABI/code-only APKs too, exactly like sourceDir + splitSourceDirs on-device.
                result += File(dir, entry.name).also { it.writeBytes(bytes) }
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
        const val PLAY_3085_FILE_NAME =
            "com.tencent.mm_8.0.72-3085_1arch_7dpi_24lang_2feat_985c2543ef8d5b617ac8b43f3e8e25bc_apkmirror.com.apks"
        val PLAY_3085_APKS = File("/home/ujhhgtg/Downloads", PLAY_3085_FILE_NAME)
        val SOURCE_VERIFIED_ROLES = setOf(
            // Same VIP badge consumer, but 8.0.65 uses literal #b4000000 instead of an FG_0 alias.
            "theme.color.system-surface-container-dark--system-surface-container-dark.slot-10",
            // The same rounded image placeholder changed from #66404040 to #33000000.
            "theme.color.system-surface-container-dark--system-surface-container-dark.slot-04",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-27",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-56",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-57",
            "theme.color.system-surface-container-light--10ffffff.slot-02",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-50",
            "theme.color.system-surface-light--system-surface-dark.slot-04",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-26",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-42",
            "theme.color.unknown--10ffffff.slot-06",
            "theme.color.unknown--system-surface-dark.slot-02",
        )
        const val SURFACE_CONTAINER_SLOT_57 =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-57"
        const val SURFACE_CONTAINER_SLOT_56 =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-56"
        const val SURFACE_CONTAINER_SLOT_27 =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-27"
        const val SEARCH_BAR_BACKGROUND = "theme.color.system-surface-container-light--10ffffff.slot-02"
        const val THREE_STATE_STROKE =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-50"
        const val FINDER_LIVE_TAB = "theme.color.system-surface-light--system-surface-dark.slot-04"
        const val DELETE_ACTION_COLOR =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-26"
        const val APP_BRAND_PAGE_BACKGROUND =
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-42"
        val DOMESTIC_SLOT_57 = linkedMapOf(
            "8065" to "f1",
            "8067" to "aid",
            "8069" to "aip",
            "8074" to "aj9",
            "8076" to null, "8077" to null, "8078" to null,
        )
        val DOMESTIC_SEARCH_BAR = mapOf(
            "8065" to null, "8067" to null, "8069" to null,
            "8074" to "al1", "8076" to "akt", "8077" to "al3", "8078" to "alc",
        )
        val DOMESTIC_FINDER_LIVE_TAB = mapOf(
            "8065" to "ai9", "8067" to "aiy", "8069" to "aja", "8074" to "ak7", "8076" to "ajz",
            "8077" to "ak4", "8078" to "akc",
        )
        val DOMESTIC_VIP_FOREGROUND = mapOf(
            "8065" to "mc", "8067" to "aja", "8069" to "ajo", "8074" to "akp",
            "8076" to "akh", "8077" to "akr", "8078" to "al1",
        )
        val DOMESTIC_FEEDBACK_BACKGROUND = mapOf(
            "8065" to "ey", "8067" to "aic", "8069" to "aio", "8074" to "aj8",
            "8076" to null, "8077" to null, "8078" to null,
        )
        // Independent, APK-verified expectations: removed layouts and shared old search colors.
        val EXPECTED_ABSENT = mapOf(
            "wechat_8065.apk" to setOf(SEARCH_BAR_BACKGROUND,
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-47",
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-60",
                "theme.color.system-surface-container-light--10ffffff.slot-03",
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-58",
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-59"),
            "wechat_8067.apk" to setOf(SEARCH_BAR_BACKGROUND,
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-58",
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-59"),
            "wechat_8069.apk" to setOf(SEARCH_BAR_BACKGROUND,
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-53"),
            "wechat_8069_3020_play.apk" to setOf(SEARCH_BAR_BACKGROUND,
                "theme.color.system-surface-container-light--system-surface-container-dark.slot-53"),
            "wechat_8072_3084.apks" to emptySet(),
            PLAY_3085_FILE_NAME to emptySet(),
            "wechat_8074.apk" to emptySet(),
            "wechat_8076.apk" to setOf(SURFACE_CONTAINER_SLOT_56, SURFACE_CONTAINER_SLOT_57),
            "wechat_8077.apk" to setOf(SURFACE_CONTAINER_SLOT_56, SURFACE_CONTAINER_SLOT_57),
            "wechat_8078.apk" to setOf(SURFACE_CONTAINER_SLOT_56, SURFACE_CONTAINER_SLOT_57,
                "theme.color.10000000--system-surface-container-dark.slot-02",
                "theme.color.system-primary-light--system-primary-dark.slot-12"),
        )
        val HOST_FOREGROUND_COLORS = setOf("FG_0", "FG_1", "FG_2", "a0b", "a0c", "BW_70")
        val DEX_NAME = Regex("classes(\\d*)?\\.dex")
        val EXPECTED_ROLES = listOf(
            "main.surface.header.primary",
            "main.surface.header.secondary",
            "chat.bubble.incoming.pro",
            "chat.bubble.incoming.pro.handled",
            "chat.bubble.outgoing.pro",
            "chat.bubble.outgoing.pro.handled",
            "chat.red-envelope.incoming.alias",
            "chat.red-envelope.outgoing.alias",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-26",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-27",
            "theme.color.system-surface-container-light--system-surface-container-dark.slot-42",
            "theme.color.unknown--10ffffff.slot-06",
            "theme.color.unknown--system-surface-dark.slot-02",
        )
        val FIXED_TARGETS = listOf(
            "c2creceivermsgnodebg", "c2creceivermsgnodebg_handled",
            "c2csendermsgnodebg", "c2csendermsgnodebg_handled",
            "redcoverreceivermsgnodebg", "redcoversendermsgnodebg",
        )
        val EXPECTED_TARGETS = mapOf(
            "wechat_8065.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8067.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8069.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8074.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8076.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8077.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8078.apk" to listOf("ao1", "ao2") + FIXED_TARGETS + listOf("tt", "up", "dp", "rh", "e2"),
            "wechat_8069_3020_play.apk" to listOf("can", "cao") + FIXED_TARGETS + listOf("adl", "af0", "n0", "a_z", "ni"),
            "wechat_8072_3084.apks" to
                listOf("cbr", "cbs") + FIXED_TARGETS + listOf("adr", "af6", "n0", "aa4", "ni"),
            PLAY_3085_FILE_NAME to
                listOf("cbr", "cbs") + FIXED_TARGETS + listOf("adr", "af6", "n0", "aa4", "ni"),
        ).mapValues { (_, names) -> EXPECTED_ROLES.zip(names).toMap() }
        val INCOMING_TRANSFER_ROLES = listOf("chat.transfer.incoming.expired", "chat.transfer.incoming.received")
        val OUTGOING_TRANSFER_ROLES = listOf("chat.transfer.outgoing.expired", "chat.transfer.outgoing.received")
        val STANDARD_TRANSFER_TARGETS = setOf("c2c_chatfrom_remittance_expired_bg", "k6") to
            setOf("c2c_chatto_remittance_expired_bg", "k9")
        val EXPECTED_TRANSFER_TARGETS = mapOf(
            "wechat_8065.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8067.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8069.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8074.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8076.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8077.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8078.apk" to STANDARD_TRANSFER_TARGETS,
            "wechat_8069_3020_play.apk" to
                (setOf("c2c_chatfrom_remittance_expired_bg", "ym") to setOf("c2c_chatto_remittance_expired_bg", "yy")),
            "wechat_8072_3084.apks" to
                (setOf("c2c_chatfrom_remittance_expired_bg", "z1") to setOf("c2c_chatto_remittance_expired_bg", "zc")),
            PLAY_3085_FILE_NAME to
                (setOf("c2c_chatfrom_remittance_expired_bg", "z1") to setOf("c2c_chatto_remittance_expired_bg", "zc")),
        )
    }
}
