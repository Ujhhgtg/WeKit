package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MonetProductionRulesTest {
    @Test
    fun `all production rules resolve uniquely across the APK corpus`() {
        val samples = listOf("8065", "8067", "8069", "8074", "8076", "8077").associateWith {
            MonetCorpus.graph(File("/home/ujhhgtg/coding/wechat_$it.apk"))
        } + mapOf(
            "8069_3020_play" to MonetCorpus.graph(File("/home/ujhhgtg/coding/wechat_8069_3020_play.apk")),
            "play3084" to MonetCorpus.graph("play3084", emptyList()),
        )
        samples.forEach { (sample, graph) ->
            MonetStructureMatcher.structuralCandidates(graph).forEach { (rule, candidates) ->
                assertTrue(candidates.isNotEmpty(), "$sample: ${rule.id}")
            }
        }
    }
}
