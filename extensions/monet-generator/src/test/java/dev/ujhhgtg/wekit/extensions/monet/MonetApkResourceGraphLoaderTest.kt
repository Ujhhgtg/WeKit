package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MonetApkResourceGraphLoaderTest {

    @Test
    fun `loader reads colors drawables and xml references without framework apk`() {
        val graph = MonetApkResourceGraphLoader.load(
            listOf(File("../../app/embedded/monet/template_api31.apk")),
            "dev.ujhhgtg.wekit.monetengine.overlay",
        )

        assertNotNull(graph.node(MonetResourceKey("color", "Brand")))
        assertTrue(graph.nodes("drawable").isNotEmpty())
        assertTrue(graph.xmlOwners().isNotEmpty())
    }
}
