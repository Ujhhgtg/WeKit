package dev.ujhhgtg.wekit.extensions.monet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class MonetS4PayloadTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "template_base_api31.apk",
            "template_base_api34.apk",
            "template_classic.apk",
            "template_pro.apk",
            "template_corners.apk",
            "template_solid_tab.apk",
            "template_blur_tab.apk",
        ],
    )
    fun `S4 template loads with no framework or dangling resource file`(name: String) {
        loadMonetTemplate(File("../../app/embedded/monet/templates", name)).use { apk ->
            val expected = TEMPLATE_METADATA.getValue(name)
            assertTrue(apk.loadedFrameworks.isEmpty())
            assertFalse(apk.hasSignatureBlock())
            assertTrue(apk.listResFiles().all { apk.getInputSource(it.filePath) != null })
            assertEquals(expected.first, apk.androidManifest.packageName)
            assertEquals(expected.first, apk.tableBlock.pickOne().name)
            assertEquals(expected.second, apk.androidManifest.minSdkVersion)
            assertEquals(36, apk.androidManifest.targetSdkVersion)
        }
    }

    @Test
    fun `catalog has unique roles and every template binding exists`() {
        val catalog = MonetRoleCatalog.load(File("../../app/embedded/monet"))

        assertEquals(231, catalog.roles.size)
        assertEquals(7, catalog.overlays.size)
        assertEquals(catalog.roles.size, catalog.roles.map { it.id }.toSet().size)
        assertTrue(
            catalog.overlays.all { overlay ->
                overlay.templateResources.keys.all(catalog::hasRole)
            },
        )
        assertEquals(
            catalog.roles.map { it.id }.toSet() - AUXILIARY_ROLE_IDS,
            catalog.overlays.flatMap { it.templateResources.keys }.toSet(),
        )
        assertEquals(
            mapOf(
                "color" to 191,
                "drawable" to 30,
                "layout" to 1,
                "mipmap" to 1,
                "string" to 7,
                "style" to 1,
            ),
            catalog.roles.groupingBy { it.type }.eachCount(),
        )
        val roles = catalog.roles.associateBy { it.id }
        assertEquals(
            listOf("chat.input.container"),
            roles.getValue("chat.input.background").requiredIncomingRoleIds,
        )
        assertEquals(
            listOf("chat.input.container"),
            roles.getValue("chat.quote.background").requiredIncomingRoleIds,
        )
        assertEquals(
            listOf("payment.keyboard.key.style"),
            roles.getValue("payment.key.pressed").requiredIncomingRoleIds,
        )
        val keyboardStyle = roles.getValue("payment.keyboard.key.style")
        assertEquals(null, keyboardStyle.defaultValue)
        assertFalse(keyboardStyle.defaultValueStructure.orEmpty().contains("res/"))
    }

    @Test
    fun `only verified digest keyed profiles are exact selectable profiles`() {
        val payload = File("../../app/embedded/monet")
        val catalog = MonetRoleCatalog.load(payload)
        val profiles = Json.parseToJsonElement(payload.resolve("monet_profiles.json").readText()).jsonObject

        assertEquals("monet-resource-graph-v1", profiles.getValue("digestAlgorithm").jsonPrimitive.content)
        val verified = profiles.getValue("verifiedProfiles").jsonArray
        assertEquals(1, verified.size)
        val play3084 = verified.single().jsonObject
        assertEquals(3084, play3084.getValue("versionCode").jsonPrimitive.int)
        assertEquals(PLAY_3084_GRAPH_DIGEST, play3084.getValue("resourceDigest").jsonPrimitive.content)
        assertEquals(
            catalog.roles.map { it.id }.toSet(),
            play3084.getValue("roles").jsonObject.keys,
        )

        val structural = profiles.getValue("structuralOnlyProfiles").jsonArray
        val play3085 = structural.single { profile ->
            profile.jsonObject["versionCode"]?.jsonPrimitive?.int == 3085
        }.jsonObject
        assertFalse(play3085.getValue("selectable").jsonPrimitive.boolean)
        assertEquals(null, play3085["resourceDigest"]?.jsonPrimitive?.contentOrNull)
        assertTrue(verified.none { profile ->
            profile.jsonObject["versionCode"]?.jsonPrimitive?.int == 3085
        })
        val domestic = structural.filter { profile ->
            profile.jsonObject["channel"]?.jsonPrimitive?.content == "domestic"
        }
        assertEquals(5, domestic.size)
        assertTrue(domestic.all { profile ->
            val value = profile.jsonObject
            val sourceEvidence = value.getValue("sourceEvidence").jsonObject
            val expected = DOMESTIC_PROVENANCE.getValue(value.getValue("versionName").jsonPrimitive.content)
            value.getValue("selectable").jsonPrimitive.boolean.not() &&
                "resourceDigest" !in value &&
                sourceEvidence.getValue("resourceFileCount").jsonPrimitive.int == expected.first &&
                sourceEvidence.getValue("resourceSnapshotSha256").jsonPrimitive.content == expected.second
        })
    }

    @Test
    fun `templates contain no signatures and Classic repair uses exact S4 binary xml`() {
        val templates = File("../../app/embedded/monet/templates")
        TEMPLATE_METADATA.keys.forEach { name ->
            ZipFile(templates.resolve(name)).use { archive ->
                assertTrue(archive.entries().asSequence().none { it.name.startsWith("META-INF/") })
            }
        }
        ZipFile(templates.resolve("template_classic.apk")).use { archive ->
            val repair = archive.getInputStream(
                archive.getEntry("res/drawable/chat_voice_to_text.xml"),
            ).readBytes()
            assertEquals(CLASSIC_REPAIR_SHA256, sha256(repair))
        }
    }

    private companion object {
        const val PLAY_3084_GRAPH_DIGEST =
            "0235e64f66ad276867de2482c2a3fd62daef0202b3061330ef0f6cf8db434ed9"
        const val CLASSIC_REPAIR_SHA256 =
            "c172c38d941bc89dba127fc1df5b3015dbc591cabc779639fc7b5fae5d787ed8"
        val AUXILIARY_ROLE_IDS = setOf(
            "chat.input.container",
            "payment.keyboard.key.style",
        )
        val DOMESTIC_PROVENANCE = mapOf(
            "8.0.65" to (
                10453 to "fd647afef73bdb0e029db61654c629e048df296a7fee46691ea0751a73ece47c"
            ),
            "8.0.67" to (
                10589 to "17dca358cbe119747319fe5a76a01beb91130735c66f2c9f03e3d00deac2e3cc"
            ),
            "8.0.69" to (
                10709 to "c627fe9ed6afc27d9402b69e9918ab18697623985cfc7a031eac47b99d9393e4"
            ),
            "8.0.74" to (
                10895 to "6e5195f4f23a5e7f477938be5b0023fe9e41c474d077a936443b1e7d4a2cf00c"
            ),
            "8.0.76" to (
                10931 to "ef8489e7c1e8c8a40d1ee077130eaf361d44a95af5e7cb7c6ffad3ea28ce17fd"
            ),
        )
        val TEMPLATE_METADATA = mapOf(
            "template_base_api31.apk" to ("monet.com.tencent.mm" to 31),
            "template_base_api34.apk" to ("monet.com.tencent.mm" to 34),
            "template_classic.apk" to ("monet.classicbubble.com.tencent.mm" to 31),
            "template_pro.apk" to ("monet.bubblepro.com.tencent.mm" to 31),
            "template_corners.apk" to ("monet.multiscenecorners.com.tencent.mm" to 31),
            "template_solid_tab.apk" to ("monet.solidtab.com.tencent.mm" to 31),
            "template_blur_tab.apk" to ("monet.blurtab.com.tencent.mm" to 31),
        )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
