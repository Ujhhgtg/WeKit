package dev.ujhhgtg.wekit.extensions.monet

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.ApkSigningBlockUtils
import com.android.apksig.util.DataSources
import com.reandroid.archive.block.SignatureId
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBlurPalette
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import com.android.apksig.internal.util.Pair as ApkSigPair

class MonetOverlayBuilderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `classic corners blur selection builds only requested signed overlays`() {
        val built = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.CLASSIC, corners = true, MonetTabStyle.BLUR),
            blurPalette = blurPalette(),
        ).builder.buildAll(tempDir.resolve("selected"))

        assertEquals(
            setOf("base-api31", "classic-bubble", "multi-scene-corners", "blur-tab"),
            built.map(MonetBuiltOverlay::overlayId).toSet(),
        )
        assertEquals(
            setOf(
                "MonetWeChat.apk",
                "MonetWeChatClassicBubble.apk",
                "MonetWeChatMultiSceneCorners.apk",
                "MonetWeChatBlurTab.apk",
            ),
            built.map { it.file.name }.toSet(),
        )
        built.forEach { overlay ->
            val verification = ApkVerifier.Builder(overlay.file).build().verify()
            assertTrue(verification.isVerified, overlay.fileName)
            assertFalse(verification.isVerifiedUsingV1Scheme, overlay.fileName)
            assertTrue(verification.isVerifiedUsingV3Scheme, overlay.fileName)
            loadMonetTemplate(overlay.file).use { apk ->
                assertNotNull(apk.apkSignatureBlock.getSignature(SignatureId.V2), overlay.fileName)
                assertNotNull(apk.apkSignatureBlock.getSignature(SignatureId.V3), overlay.fileName)
            }
        }
    }

    @Test
    fun `template target rename preserves resource id and leaves helper entries alone`() {
        val fixture = fixture(
            sdkInt = 31,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            keyOverrides = mapOf(
                INCOMING_BUBBLE_ROLE to MonetResourceKey("drawable", "domestic_incoming"),
            ),
        )
        val definition = fixture.catalog.overlay("base-api31")
        val template = PAYLOAD_DIR.resolve(definition.templateFile)
        val templateBinding = definition.templateResources.getValue(INCOMING_BUBBLE_ROLE)
        val originalId: Int
        val helper: Pair<MonetResourceKey, Int>
        loadMonetTemplate(template).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            originalId = requireNotNull(pkg.getResource(templateBinding.type, templateBinding.name)).resourceId
            helper = requireNotNull(firstUnboundResource(pkg, definition.templateResources.values.toSet()))
        }

        val built = fixture.builder.build(definition, tempDir.resolve("renamed.apk"))

        loadMonetTemplate(built.file).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val renamed = requireNotNull(pkg.getResource("drawable", "domestic_incoming"))
            assertEquals(originalId, renamed.resourceId)
            assertNull(pkg.getResource(templateBinding.type, templateBinding.name))
            assertEquals(helper.second, requireNotNull(pkg.getResource(helper.first.type, helper.first.name)).resourceId)
        }
        assertTrue(INCOMING_BUBBLE_ROLE in built.roleIds)
        assertTrue(built.rewritten > 0)
        assertEquals(0, built.added)
    }

    @Test
    fun `Android 12 prunes themed icon while Android 13 retains it`() {
        val sdk31 = fixture(
            sdkInt = 31,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
        )
        val sdk31Base = sdk31.builder.build(
            sdk31.catalog.overlay("base-api31"),
            tempDir.resolve("sdk31.apk"),
        )
        val sdk33 = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
        )
        val sdk33Base = sdk33.builder.build(
            sdk33.catalog.overlay("base-api31"),
            tempDir.resolve("sdk33.apk"),
        )

        assertFalse(THEMED_ICON_ROLE in sdk31Base.roleIds)
        assertTrue(THEMED_ICON_ROLE in sdk31Base.skippedRoleIds)
        assertTrue(THEMED_ICON_ROLE in sdk33Base.roleIds)
        loadMonetTemplate(sdk31Base.file).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val themed = pkg.getResource("mipmap", "b")
            assertTrue(themed == null || themed.isEmpty)
        }
        loadMonetTemplate(sdk33Base.file).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val themed = requireNotNull(pkg.getResource("mipmap", "b"))
            assertFalse(themed.isEmpty)
        }
    }

    @Test
    fun `supported optional role may be explicitly skipped and pruned`() {
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            explicitlySkippedRoleIds = setOf(THEMED_ICON_ROLE),
        )

        val built = fixture.builder.build(
            fixture.catalog.overlay("base-api31"),
            tempDir.resolve("optional-skip.apk"),
        )

        assertFalse(THEMED_ICON_ROLE in built.roleIds)
        assertTrue(THEMED_ICON_ROLE in built.skippedRoleIds)
        loadMonetTemplate(built.file).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val themed = pkg.getResource("mipmap", "b")
            assertTrue(themed == null || themed.isEmpty)
        }
    }

    @Test
    fun `evidence-only helper roles are not overlay build dependencies`() {
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            omittedRoleIds = EVIDENCE_ONLY_ROLE_IDS,
        )

        val built = fixture.builder.build(
            fixture.catalog.overlay("base-api31"),
            tempDir.resolve("without-evidence-helpers.apk"),
        )

        assertTrue(built.file.isFile)
        assertTrue(built.roleIds.intersect(EVIDENCE_ONLY_ROLE_IDS).isEmpty())
    }

    @Test
    fun `SDK branches rewrite manifest and signer minimum together`() {
        listOf(
            Triple(31, "base-api31", 31 to 33),
            Triple(33, "base-api31", 31 to 33),
            Triple(34, "base-api34", 34 to 36),
        ).forEach { (sdkInt, overlayId, expectedSdk) ->
            val fixture = fixture(
                sdkInt = sdkInt,
                options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            )
            val definition = fixture.catalog.overlay(overlayId)
            val built = fixture.builder.build(definition, tempDir.resolve("manifest-$sdkInt.apk"))

            loadMonetTemplate(built.file).use { apk ->
                assertEquals(definition.packageName, apk.androidManifest.packageName)
                assertEquals("com.tencent.mm", apk.androidManifest.overlayTargetPackage())
                assertEquals(expectedSdk.first, apk.androidManifest.minSdkVersion)
                assertEquals(expectedSdk.second, apk.androidManifest.targetSdkVersion)
            }
            val verification = ApkVerifier.Builder(built.file)
                .setMinCheckedPlatformVersion(expectedSdk.first)
                .setMaxCheckedPlatformVersion(expectedSdk.second)
                .build()
                .verify()
            assertTrue(verification.isVerified, "SDK $sdkInt")
            assertTrue(verification.isVerifiedUsingV3Scheme, "SDK $sdkInt")
            loadMonetTemplate(built.file).use { apk ->
                assertNotNull(apk.apkSignatureBlock.getSignature(SignatureId.V2), "SDK $sdkInt")
                assertNotNull(apk.apkSignatureBlock.getSignature(SignatureId.V3), "SDK $sdkInt")
            }
        }
    }

    @Test
    fun `signer verification rejects a corrupt v2 block even when v3 remains valid`() {
        val signed = signApi31Template("corrupt-source.apk")
        val corrupt = rewriteSigningBlock(signed, "corrupt-v2.apk") { blocks ->
            replaceV2Block(blocks) { v2 ->
                v2.clone().also { bytes ->
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                }
            }
        }

        assertV3StillVerifies(corrupt)
        loadMonetTemplate(corrupt).use { apk ->
            assertNotNull(apk.apkSignatureBlock.getSignature(SignatureId.V2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonetApkSigner.verifySignedApk(corrupt)
        }
    }

    @Test
    fun `signer verification rejects a missing v2 block even when v3 remains valid`() {
        val signed = signApi31Template("missing-source.apk")
        val missing = rewriteSigningBlock(signed, "missing-v2.apk") { blocks ->
            blocks.map { block ->
                if (block.second == SignatureId.V2.id) {
                    ApkSigPair.of(block.first, MISSING_V2_TEST_BLOCK_ID)
                } else {
                    block
                }
            }
        }

        assertV3StillVerifies(missing)
        loadMonetTemplate(missing).use { apk ->
            assertNull(apk.apkSignatureBlock.getSignature(SignatureId.V2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonetApkSigner.verifySignedApk(missing)
        }
    }

    @Test
    fun `signer verification rejects different v2 and v3 signer identities`() {
        val signed = signApi31Template("identity-source.apk")
        val alternate = signApi31Template("identity-alternate.apk")
        val alternateV2 = readSignatureBlocks(alternate)
            .single { it.second == SignatureId.V2.id }
            .first
        val mismatched = rewriteSigningBlock(signed, "identity-mismatch.apk") { blocks ->
            replaceV2Block(blocks) { alternateV2 }
        }

        assertV3StillVerifies(mismatched)
        val v2Verification = ApkVerifier.Builder(mismatched)
            .setMinCheckedPlatformVersion(24)
            .setMaxCheckedPlatformVersion(27)
            .build()
            .verify()
        assertTrue(v2Verification.isVerified)
        assertTrue(v2Verification.isVerifiedUsingV2Scheme)
        assertThrows(IllegalArgumentException::class.java) {
            MonetApkSigner.verifySignedApk(mismatched)
        }
    }

    @Test
    fun `BlurTab writes fixed alpha literals and records palette sources`() {
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.BLUR),
            blurPalette = MonetBlurPalette(
                lightRgb = 0x123456,
                nightRgb = 0xabcdef,
                lightSource = "system_surface_container_light",
                nightSource = "system_surface_container_dark",
            ),
            keyOverrides = mapOf(
                TAB_BACKGROUND_ROLE to MonetResourceKey("color", "domestic_tab_background"),
            ),
        )
        val definition = fixture.catalog.overlay("blur-tab")

        val built = fixture.builder.build(definition, tempDir.resolve("blur.apk"))

        loadMonetTemplate(built.file).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val color = requireNotNull(pkg.getResource("color", "domestic_tab_background"))
            val entries = color.iterator(false)
            var count = 0
            while (entries.hasNext()) {
                val entry = requireNotNull(entries.next())
                if (entry.isNull) continue
                count++
                val isNight = entry.resConfig.qualifiers.orEmpty()
                    .split('-')
                    .contains("night")
                assertEquals(ValueType.COLOR_ARGB8, entry.valueType)
                assertEquals(
                    if (isNight) 0xc7abcdefL.toInt() else 0xb0123456L.toInt(),
                    entry.resValue.data,
                )
            }
            assertEquals(2, count)
        }
        val palette = requireNotNull(built.diagnostics.blurPalette)
        assertEquals(0xb0123456L, palette.lightArgb)
        assertEquals(0xc7abcdefL, palette.nightArgb)
        assertEquals("system_surface_container_light", palette.lightSource)
        assertEquals("system_surface_container_dark", palette.nightSource)
    }

    @Test
    fun `missing bound role fails before any overlay is written`() {
        val output = tempDir.resolve("missing")
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            omittedRoleIds = setOf(INCOMING_BUBBLE_ROLE),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.builder.buildAll(output)
        }

        assertTrue(error.message.orEmpty().contains(INCOMING_BUBBLE_ROLE))
        assertFalse(output.exists())
    }

    @Test
    fun `wrong type resolution fails before any overlay is written`() {
        val output = tempDir.resolve("wrong-type")
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID),
            keyOverrides = mapOf(
                INCOMING_BUBBLE_ROLE to MonetResourceKey("color", "wrong_type_incoming"),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.builder.buildAll(output)
        }

        assertTrue(error.message.orEmpty().contains(INCOMING_BUBBLE_ROLE))
        assertFalse(output.exists())
    }

    @Test
    fun `blur selection without a palette fails before any overlay is written`() {
        val output = tempDir.resolve("no-palette")
        val fixture = fixture(
            sdkInt = 33,
            options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.BLUR),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.builder.buildAll(output)
        }

        assertTrue(error.message.orEmpty().contains("BlurTab"))
        assertFalse(output.exists())
    }

    private fun fixture(
        sdkInt: Int,
        options: MonetGenerationOptions,
        blurPalette: MonetBlurPalette? = null,
        keyOverrides: Map<String, MonetResourceKey> = emptyMap(),
        omittedRoleIds: Set<String> = emptySet(),
        explicitlySkippedRoleIds: Set<String> = emptySet(),
    ): BuilderFixture {
        val catalog = MonetRoleCatalog.load(PAYLOAD_DIR)
        val exactRoles = MonetProfileCatalog.load(PAYLOAD_DIR)
            .verifiedProfiles
            .single()
            .roles
        val nodes = mutableListOf<MonetResourceNode>()
        val resolved = linkedMapOf<String, MonetResolvedRole>()
        val skipped = mutableListOf<MonetRoleDiagnostic>()
        val diagnostics = linkedMapOf<String, MonetRoleDiagnostic>()
        catalog.roles.forEachIndexed { index, role ->
            val key = keyOverrides[role.id] ?: exactRoles.getValue(role.id)
            val resourceId = 0x7f010000 + index
            nodes += MonetResourceNode(resourceId, key, emptyList())
            if (role.id in omittedRoleIds) return@forEachIndexed
            if (
                sdkInt < role.minSdk ||
                role.maxSdk?.let { sdkInt > it } == true ||
                role.id in explicitlySkippedRoleIds
            ) {
                val diagnostic = MonetRoleDiagnostic(
                    roleId = role.id,
                    core = role.core,
                    failure = if (role.id in explicitlySkippedRoleIds) {
                        MonetResolutionFailure.NOT_FOUND
                    } else {
                        MonetResolutionFailure.SDK_UNSUPPORTED
                    },
                    candidateIds = emptyList(),
                    stages = emptyList(),
                )
                skipped += diagnostic
                diagnostics[role.id] = diagnostic
            } else {
                resolved[role.id] = MonetResolvedRole(role.id, resourceId, key, profileMatched = true)
                diagnostics[role.id] = MonetRoleDiagnostic(
                    roleId = role.id,
                    core = role.core,
                    candidateIds = listOf(resourceId),
                    stages = emptyList(),
                )
            }
        }
        val graph = MonetResourceGraph(nodes)
        val report = MonetResolutionReport(resolved, skipped, diagnostics)
        return BuilderFixture(
            catalog,
            MonetOverlayBuilder(
                payloadDir = PAYLOAD_DIR,
                catalog = catalog,
                resolution = report,
                targetGraph = graph,
                options = options,
                sdkInt = sdkInt,
                blurPalette = blurPalette,
            ),
        )
    }

    private fun options(
        bubbleStyle: MonetBubbleStyle,
        corners: Boolean,
        tabStyle: MonetTabStyle,
    ) = MonetGenerationOptions(bubbleStyle, corners, tabStyle, MonetUserScope.CURRENT)

    private fun blurPalette() = MonetBlurPalette(
        lightRgb = 0x123456,
        nightRgb = 0x654321,
        lightSource = "light-source",
        nightSource = "night-source",
    )

    private fun signApi31Template(fileName: String): File =
        tempDir.resolve(fileName).also { output ->
            MonetApkSigner.sign(
                PAYLOAD_DIR.resolve("templates/template_base_api31.apk"),
                output,
                minSdk = 31,
            )
        }

    private fun rewriteSigningBlock(
        source: File,
        outputName: String,
        rewrite: (
            List<ApkSigPair<ByteArray, Int>>,
        ) -> List<ApkSigPair<ByteArray, Int>>,
    ): File = tempDir.resolve(outputName).also { output ->
        source.copyTo(output)
        RandomAccessFile(output, "rw").use { apk ->
            val dataSource = DataSources.asDataSource(apk)
            val signingBlock = ApkUtils.findApkSigningBlock(dataSource)
            val rewritten = ApkSigningBlockUtils.generateApkSigningBlock(
                rewrite(ApkSigningBlockUtils.getApkSignatureBlocks(signingBlock.contents)),
            )
            require(rewritten.size.toLong() == signingBlock.contents.size()) {
                "Test signing-block rewrite changed its size"
            }
            apk.seek(signingBlock.startOffset)
            apk.write(rewritten)
        }
    }

    private fun readSignatureBlocks(apk: File): List<ApkSigPair<ByteArray, Int>> =
        RandomAccessFile(apk, "r").use { input ->
            ApkSigningBlockUtils.getApkSignatureBlocks(
                ApkUtils.findApkSigningBlock(DataSources.asDataSource(input)).contents,
            )
        }

    private fun replaceV2Block(
        blocks: List<ApkSigPair<ByteArray, Int>>,
        replacement: (ByteArray) -> ByteArray,
    ): List<ApkSigPair<ByteArray, Int>> {
        require(blocks.count { it.second == SignatureId.V2.id } == 1)
        return blocks.map { block ->
            if (block.second == SignatureId.V2.id) {
                ApkSigPair.of(replacement(block.first), block.second)
            } else {
                block
            }
        }
    }

    private fun assertV3StillVerifies(apk: File) {
        val verification = ApkVerifier.Builder(apk).build().verify()
        assertTrue(verification.isVerified)
        assertTrue(verification.isVerifiedUsingV3Scheme)
    }

    private fun MonetRoleCatalog.overlay(id: String): MonetOverlayDefinition =
        overlays.single { it.id == id }

    private fun firstUnboundResource(
        pkg: PackageBlock,
        bindings: Set<MonetResourceKey>,
    ): Pair<MonetResourceKey, Int>? {
        listOf("color", "drawable", "mipmap", "string").forEach { type ->
            val resources = pkg.getResources(type)
            while (resources.hasNext()) {
                val resource = resources.next()
                val name = resource.name ?: continue
                val key = MonetResourceKey(type, name)
                if (key !in bindings && !resource.isEmpty) return key to resource.resourceId
            }
        }
        return null
    }

    private data class BuilderFixture(
        val catalog: MonetRoleCatalog,
        val builder: MonetOverlayBuilder,
    )

    private companion object {
        val PAYLOAD_DIR = File("../../app/embedded/monet")
        const val INCOMING_BUBBLE_ROLE = "chat.bubble.incoming.normal"
        const val THEMED_ICON_ROLE = "launcher.themed.icon"
        const val TAB_BACKGROUND_ROLE = "main.tab.background"
        const val MISSING_V2_TEST_BLOCK_ID = 0x12345678
        val EVIDENCE_ONLY_ROLE_IDS = setOf(
            "chat.input.container",
            "payment.keyboard.key.style",
        )
    }
}

private fun com.reandroid.arsc.chunk.xml.AndroidManifestBlock.overlayTargetPackage(): String? =
    manifestElement
        ?.getElement("overlay")
        ?.searchAttributeByName("targetPackage")
        ?.valueAsString
