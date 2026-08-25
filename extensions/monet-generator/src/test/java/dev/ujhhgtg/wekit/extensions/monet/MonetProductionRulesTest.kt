package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class MonetProductionRulesTest {
    @Test
    fun `all production rules resolve uniquely across the APK corpus`() {
        val samples = listOf("8065", "8067", "8069", "8074", "8076", "8077").map {
            File("/home/ujhhgtg/coding/wechat_$it.apk")
        } + File("/home/ujhhgtg/coding/wechat_8069_3020_play.apk")
        samples.forEach { apk ->
            assertEquals(MONET_RULES.size, MonetStructureMatcher.resolveAll(MonetCorpus.graph(apk)).size, apk.name)
        }
    }
}
