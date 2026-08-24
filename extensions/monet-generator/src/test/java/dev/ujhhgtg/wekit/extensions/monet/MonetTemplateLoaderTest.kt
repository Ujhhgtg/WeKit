package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class MonetTemplateLoaderTest {

    @ParameterizedTest
    @ValueSource(strings = ["template_base_api31.apk", "template_base_api34.apk"])
    fun `template loads without bundled Android framework resources`(name: String) {
        val template = File("../../app/embedded/monet/templates", name)

        loadMonetTemplate(template).use { apk ->
            assertNotNull(apk.tableBlock.pickOne())
            assertTrue(apk.loadedFrameworks.isEmpty())
        }
    }
}
