package dev.ujhhgtg.wekit.dexkit.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CloudDexReportTest {
    private val host = CloudDexHost("8.0.69", 3040, false)
    private val firstItem = CurrentDexItem(
        technicalId = "FirstFeature",
        methodHash = "first-hash",
        delegateKeys = setOf("FirstFeature:class", "FirstFeature:method"),
    )
    private val secondItem = CurrentDexItem(
        technicalId = "SecondFeature",
        methodHash = "second-hash",
        delegateKeys = setOf("SecondFeature:method"),
    )

    @Test
    fun canonicalAssetNameSeparatesDomesticAndGooglePlayBuilds() {
        assertEquals(
            "wechat-8.0.69-3040-domestic.json",
            CloudDexReport.assetName(host),
        )
        assertEquals(
            "wechat-8.0.69-3020-google-play.json",
            CloudDexReport.assetName(CloudDexHost("8.0.69", 3020, true)),
        )
    }

    @Test
    fun validReportSelectsEveryCurrentItemInInputOrder() {
        val selection = CloudDexReport.select(validReport(), host, listOf(secondItem, firstItem))

        assertEquals(
            listOf(
                CloudDexCacheEntry(
                    technicalId = "SecondFeature",
                    methodHash = "second-hash",
                    descriptors = mapOf("SecondFeature:method" to "Lsecond;->method()V"),
                ),
                CloudDexCacheEntry(
                    technicalId = "FirstFeature",
                    methodHash = "first-hash",
                    descriptors = mapOf(
                        "FirstFeature:class" to "first.Class",
                        "FirstFeature:method" to "Lfirst/Class;->method()V",
                    ),
                ),
            ),
            selection.entries,
        )
        assertEquals(0, selection.rejectedCount)
    }

    @Test
    fun expectedFailureFeatureAndDelegateRemainEligible() {
        val report = validReport().replace(
            "\"outcome\": \"PASS\",\n          \"elapsedMillis\": 1,\n          \"delegates\": [\n            {\"key\": \"SecondFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lsecond;->method()V\", \"isPlaceholder\": false}",
            "\"outcome\": \"PASS_WITH_EXPECTED_FAILURES\",\n          \"elapsedMillis\": 1,\n          \"delegates\": [\n            {\"key\": \"SecondFeature:method\", \"status\": \"EXPECTED_FAILURE\", \"descriptor\": \"Lsecond;->method()V\", \"isPlaceholder\": true}",
        )

        val selection = CloudDexReport.select(report, host, listOf(secondItem))

        assertEquals(1, selection.entries.size)
        assertEquals(0, selection.rejectedCount)
    }

    @Test
    fun staleItemIsRejectedWithoutDiscardingOtherMatches() {
        val report = validReport().replace("\"methodHash\": \"first-hash\"", "\"methodHash\": \"stale\"")

        val selection = CloudDexReport.select(report, host, listOf(firstItem, secondItem))

        assertEquals(listOf("SecondFeature"), selection.entries.map { it.technicalId })
        assertEquals(1, selection.rejectedCount)
    }

    @Test
    fun incompleteOrFailedDelegatesRejectOnlyTheirItem() {
        val invalidReports = listOf(
            validReport().replace(
                firstDelegates(),
                "{\"key\": \"FirstFeature:class\", \"status\": \"SUCCESS\", \"descriptor\": \"first.Class\", \"isPlaceholder\": false}",
            ),
            validReport().replace(firstMethodDelegate(), firstMethodDelegate() + ",\n" + firstMethodDelegate()),
            validReport().replace("Lfirst/Class;->method()V", ""),
            validReport().replace(
                "\"key\": \"FirstFeature:method\", \"status\": \"SUCCESS\"",
                "\"key\": \"FirstFeature:method\", \"status\": \"UNEXPECTED_FAILURE\"",
            ),
            validReport().replace(
                "\"key\": \"FirstFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lfirst/Class;->method()V\", \"isPlaceholder\": false",
                "\"key\": \"FirstFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lfirst/Class;->method()V\", \"isPlaceholder\": true",
            ),
            validReport().replace(
                "\"key\": \"FirstFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lfirst/Class;->method()V\", \"isPlaceholder\": false",
                "\"key\": \"FirstFeature:method\", \"status\": \"EXPECTED_FAILURE\", \"descriptor\": \"Lfirst/Class;->method()V\", \"isPlaceholder\": false",
            ),
            validReport().replace(
                featureBlock("FirstFeature", "first-hash", firstDelegates()),
                featureBlock("FirstFeature", "first-hash", firstDelegates())
                    .replace("\"outcome\": \"PASS\"", "\"outcome\": \"FAIL\""),
            ),
        )

        invalidReports.forEach { report ->
            val selection = CloudDexReport.select(report, host, listOf(firstItem, secondItem))
            assertEquals(listOf("SecondFeature"), selection.entries.map { it.technicalId }, report)
            assertEquals(1, selection.rejectedCount, report)
        }
    }

    @Test
    fun duplicateTargetFeatureIsRejectedWithoutDiscardingOtherMatches() {
        val firstFeature = featureBlock("FirstFeature", "first-hash", firstDelegates())
        val report = validReport().replace(firstFeature, "$firstFeature,$firstFeature")

        val selection = CloudDexReport.select(report, host, listOf(firstItem, secondItem))

        assertEquals(listOf("SecondFeature"), selection.entries.map { it.technicalId })
        assertEquals(1, selection.rejectedCount)
    }

    @Test
    fun extraReportDataDoesNotRejectCurrentItem() {
        val report = validReport()
            .replace("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"future\": true")
            .replace(
                firstMethodDelegate(),
                firstMethodDelegate() + ",\n            {\"key\": \"FirstFeature:removed\", \"status\": \"SUCCESS\", \"descriptor\": \"extra\", \"isPlaceholder\": false}",
            )
            .replace(
                featureBlock("SecondFeature", "second-hash", secondDelegates()),
                featureBlock("SecondFeature", "second-hash", secondDelegates()) +
                    "," + featureBlock("RemovedFeature", "removed", ""),
            )

        val selection = CloudDexReport.select(report, host, listOf(firstItem, secondItem))

        assertEquals(2, selection.entries.size)
        assertEquals(0, selection.rejectedCount)
    }

    @Test
    fun classNameIsNotUsedForMatching() {
        val report = validReport().replace(
            featureBlock("FirstFeature", "first-hash", firstDelegates()),
            featureBlock("FirstFeature", "first-hash", firstDelegates())
                .replace("\"technicalId\": \"FirstFeature\"", "\"technicalId\": \"RenamedFeature\""),
        )

        val selection = CloudDexReport.select(report, host, listOf(firstItem, secondItem))

        assertEquals(listOf("SecondFeature"), selection.entries.map { it.technicalId })
        assertEquals(1, selection.rejectedCount)
    }

    @Test
    fun incompatibleWholeReportIsRejected() {
        val reports = listOf(
            validReport().replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
            validReport().replaceFirst("\"outcome\": \"PASS\"", "\"outcome\": \"FAIL\""),
            validReport().replace("\"versionName\": \"8.0.69\"", "\"versionName\": \"8.0.68\""),
            validReport().replace("\"versionCode\": 3040", "\"versionCode\": 3020"),
            validReport().replace("\"isGooglePlay\": false", "\"isGooglePlay\": true"),
        )

        reports.forEach { report ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudDexReport.select(report, host, listOf(firstItem))
            }
        }
    }

    private fun validReport(): String = """
        {
          "schemaVersion": 2,
          "outcome": "PASS",
          "versionCode": 3040,
          "versionName": "8.0.69",
          "isGooglePlay": false,
          "features": [
            ${featureBlock("FirstFeature", "first-hash", firstDelegates())},
            ${featureBlock("SecondFeature", "second-hash", secondDelegates())}
          ]
        }
    """.trimIndent()

    private fun featureBlock(name: String, hash: String, delegates: String): String = """
        {
          "className": "dev.ujhhgtg.wekit.$name",
          "technicalId": "$name",
          "displayName": "$name",
          "methodHash": "$hash",
          "outcome": "PASS",
          "elapsedMillis": 1,
          "delegates": [$delegates]
        }
    """.trimIndent()

    private fun firstDelegates() = """
        {"key": "FirstFeature:class", "status": "SUCCESS", "descriptor": "first.Class", "isPlaceholder": false},
        ${firstMethodDelegate()}
    """.trimIndent()

    private fun firstMethodDelegate() =
        "{\"key\": \"FirstFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lfirst/Class;->method()V\", \"isPlaceholder\": false}"

    private fun secondDelegates() =
        "{\"key\": \"SecondFeature:method\", \"status\": \"SUCCESS\", \"descriptor\": \"Lsecond;->method()V\", \"isPlaceholder\": false}"
}
