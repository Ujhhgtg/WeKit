package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MonetProductionRulesTest {
    @Test
    fun `all production rules resolve uniquely across the APK corpus`() {
        val samples = listOf("8065", "8067", "8069", "8074", "8076", "8077").map {
            File("/home/ujhhgtg/coding/wechat_$it.apk")
        } + File("/home/ujhhgtg/coding/wechat_8069_3020_play.apk")
        samples.forEach { apk ->
            MonetStructureMatcher.structuralCandidates(MonetCorpus.graph(apk)).forEach { (rule, candidates) ->
                if (rule.requiredDexEvidence.isEmpty()) {
                    assertEquals(1, candidates.size, "${apk.name}: ${rule.id}")
                } else {
                    assertTrue(candidates.isNotEmpty(), "${apk.name}: ${rule.id}")
                }
            }
        }
    }
}
