package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.effectiveFingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CloudDexReportTest {
    private val host = CloudDexHost("8.0.69", 3040, false)
    private val owners = listOf(
        owner("owner.Consumer", "Consumer", "consumer-local"),
        owner("owner.Api", "Api", "api-local"),
        owner("owner.Core", "Core", "core-local"),
        owner("owner.Independent", "Independent", "independent-local"),
    )

    @Test
    fun canonicalAssetNamePreservesExactHostTriple() {
        assertEquals("wechat-8.0.69-3040-domestic.json", CloudDexReport.assetName(host))
        assertEquals(
            "wechat-8.0.69-3020-google-play.json",
            CloudDexReport.assetName(CloudDexHost("8.0.69", 3020, true)),
        )
    }

    @Test
    fun completeUntamperedClosureAndIndependentOwnerAreSelected() {
        val selection = CloudDexReport.select(validReport(), host, owners)

        assertEquals(listOf("Api", "Consumer", "Core", "Independent"), selection.entries.map { it.technicalId })
        assertEquals(0, selection.rejectedCount)
    }

    @Test
    fun absentDuplicateOrFailedDependencyRejectsConsumerButRetainsIndependentOwner() {
        val api = feature("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective())
        val reports = listOf(
            validReport().replace(",\n    $api", ""),
            validReport().replace(api, api.replace(delegate("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective()), delegate("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective()) + "," + delegate("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective()))),
            validReport().replace("\"id\":\"owner.Api#target\",\"status\":\"SUCCESS\"", "\"id\":\"owner.Api#target\",\"status\":\"BLOCKED\""),
            validReport().replace("\"id\":\"owner.Api#target\",\"status\":\"SUCCESS\"", "\"id\":\"owner.Api#target\",\"status\":\"INCOMPLETE\""),
            validReport().replace("\"id\":\"owner.Api#target\",\"status\":\"SUCCESS\"", "\"id\":\"owner.Api#target\",\"status\":\"UNEXPECTED_FAILURE\""),
        )

        reports.forEach { report ->
            val selection = CloudDexReport.select(report, host, owners)
            assertEquals(listOf("Core", "Independent"), selection.entries.map { it.technicalId }, report)
        }
    }

    @Test
    fun tamperedEffectiveFingerprintAndLocalFingerprintRejectClosureOnly() {
        val reports = listOf(
            validReport().replace(apiEffective(), "tampered-effective"),
            validReport().replace("\"producerFingerprint\":\"api-local\"", "\"producerFingerprint\":\"api-stale\""),
        )

        reports.forEach { report ->
            val selection = CloudDexReport.select(report, host, owners)
            assertEquals(listOf("Core", "Independent"), selection.entries.map { it.technicalId }, report)
        }
    }

    @Test
    fun delegatesMustBelongToTheirUniqueOwnerFeature() {
        val consumer = feature("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective())
        val api = feature("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective())
        val swapped = validReport()
            .replace(consumer, consumer.replace(delegate("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective()), delegate("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective())))
            .replace(api, api.replace(delegate("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective()), delegate("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective())))

        val selection = CloudDexReport.select(swapped, host, owners)

        assertEquals(listOf("Core", "Independent"), selection.entries.map { it.technicalId })
    }

    @Test
    fun delegatePlacedOnlyUnderFailedUnrelatedFeatureIsNotAccepted() {
        val consumer = feature("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective())
        val consumerDelegate = delegate("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective())
        val misplaced = validReport().replace(
            consumer,
            """{"className":"owner.Consumer","outcome":"PASS","delegates":[]},""" +
                """{"className":"owner.Unrelated","outcome":"FAIL","delegates":[$consumerDelegate]}""",
        )

        val selection = CloudDexReport.select(misplaced, host, owners)

        assertEquals(listOf("Api", "Core", "Independent"), selection.entries.map { it.technicalId })
    }

    @Test
    fun malformedOrCrossKindCloudDescriptorRejectsItsClosure() {
        val malformed = validReport().replace(
            "Lowner/Api;->target()V",
            "owner.Api",
        )

        val selection = CloudDexReport.select(malformed, host, owners)

        assertEquals(listOf("Core", "Independent"), selection.entries.map { it.technicalId })
    }

    @Test
    fun expectedPlaceholderRequiresExpectedFailurePairing() {
        val expected = validReport()
            .replace("\"id\":\"owner.Core#target\",\"status\":\"SUCCESS\"", "\"id\":\"owner.Core#target\",\"status\":\"EXPECTED_FAILURE\"")
            .replace(
                "\"descriptor\":\"Lowner/Core;->target()V\",\"isPlaceholder\":false",
                "\"descriptor\":\"$METHOD_PLACEHOLDER\",\"isPlaceholder\":true",
            )
        assertEquals(4, CloudDexReport.select(expected, host, owners).entries.size)

        val ordinaryClaimedPlaceholder = validReport()
            .replace("\"id\":\"owner.Core#target\",\"status\":\"SUCCESS\"", "\"id\":\"owner.Core#target\",\"status\":\"EXPECTED_FAILURE\"")
            .replace("\"descriptor\":\"Lowner/Core;->target()V\",\"isPlaceholder\":false", "\"descriptor\":\"Lowner/Core;->target()V\",\"isPlaceholder\":true")
        assertEquals(
            listOf("Independent"),
            CloudDexReport.select(ordinaryClaimedPlaceholder, host, owners).entries.map { it.technicalId },
        )

        val sentinelClaimedSuccess = expected
            .replace("\"status\":\"EXPECTED_FAILURE\"", "\"status\":\"SUCCESS\"")
            .replace("\"isPlaceholder\":true", "\"isPlaceholder\":false")
        assertEquals(
            listOf("Independent"),
            CloudDexReport.select(sentinelClaimedSuccess, host, owners).entries.map { it.technicalId },
        )
    }

    @Test
    fun hostSchemaAndOutcomeMismatchesRejectWholeReport() {
        listOf(
            validReport().replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            validReport().replaceFirst("\"outcome\":\"PASS\"", "\"outcome\":\"FAIL\""),
            validReport().replace("\"versionName\":\"8.0.69\"", "\"versionName\":\"8.0.68\""),
            validReport().replace("\"versionCode\":3040", "\"versionCode\":3020"),
            validReport().replace("\"isGooglePlay\":false", "\"isGooglePlay\":true"),
        ).forEach { report ->
            assertThrows(IllegalArgumentException::class.java) { CloudDexReport.select(report, host, owners) }
        }
    }

    private fun validReport() = """
        {"schemaVersion":2,"outcome":"PASS","versionCode":3040,"versionName":"8.0.69","isGooglePlay":false,"features":[
            ${feature("owner.Consumer", "consumer-local", listOf("owner.Api#target"), consumerEffective())},
            ${feature("owner.Api", "api-local", listOf("owner.Core#target"), apiEffective())},
            ${feature("owner.Core", "core-local", emptyList(), coreEffective())},
            ${feature("owner.Independent", "independent-local", emptyList(), independentEffective())}
        ]}
    """.trimIndent()

    private fun feature(owner: String, local: String, dependencies: List<String>, effective: String) =
        """{"className":"$owner","outcome":"PASS","delegates":[${delegate(owner, local, dependencies, effective)}]}"""

    private fun delegate(owner: String, local: String, dependencies: List<String>, effective: String) =
        """{"id":"$owner#target","status":"SUCCESS","descriptor":"L${owner.replace('.', '/')};->target()V","isPlaceholder":false,"producerFingerprint":"$local","effectiveFingerprint":"$effective","dependencies":[${dependencies.joinToString(",") { "\"$it\"" }}]}"""

    private fun owner(id: String, technicalId: String, local: String) = CurrentDexOwner(
        ownerId = id,
        technicalId = technicalId,
        delegates = mapOf(
            "$id#target" to CurrentDexDelegate(
                "$id#target",
                "$id#target",
                local,
                ::isValidDexMethodDescriptor,
                { it == METHOD_PLACEHOLDER },
            ),
        ),
    )

    private fun coreEffective() = fingerprint("owner.Core#target", "core-local")
    private fun apiEffective() = fingerprint("owner.Api#target", "api-local", mapOf("owner.Core#target" to coreEffective()))
    private fun consumerEffective() = fingerprint("owner.Consumer#target", "consumer-local", mapOf("owner.Api#target" to apiEffective()))
    private fun independentEffective() = fingerprint("owner.Independent#target", "independent-local")

    private fun fingerprint(id: String, local: String, dependencies: Map<String, String> = emptyMap()) =
        effectiveFingerprint(DexProducerMetadata(id, id.substringBefore('#'), null, DexProducerKind.CUSTOM, local, false), dependencies)

    private companion object {
        const val METHOD_PLACEHOLDER =
            "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"
    }
}
