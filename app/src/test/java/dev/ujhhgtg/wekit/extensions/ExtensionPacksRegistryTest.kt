package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionPacksRegistryTest {

    @Test
    fun generatedRegistryPreservesPackDisplayOrder() {
        assertEquals(
            listOf(
                "script-deps",
                "monet-generator",
                "cloudflared",
                "archlinux-arm64",
                "llama-native",
                "qwen3.8-4b-distill",
            ),
            ExtensionPacksProvider.ALL_PACKS.map(ExtensionPack::id),
        )
    }

    @Test
    fun validationRejectsEmptyPackId() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPacks(listOf(FakePack("")))
        }

        assertTrue(error.message.orEmpty().contains("FakePack"))
    }

    @Test
    fun validationRejectsDuplicatePackIds() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateExtensionPacks(listOf(FakePack("duplicate"), FakePack("duplicate")))
        }

        assertTrue(error.message.orEmpty().contains("duplicate"))
    }

    private class FakePack(
        override val id: String,
        override val displayOrder: Int = 0,
    ) : ExtensionPack {
        override val nameRes: Int = 0
        override val descriptionRes: Int = 0
        override val icon: ImageVector
            get() = error("not used by registry validation")

        override fun installDir(): File = error("not used by registry validation")

        override fun stagingDir(): File = error("not used by registry validation")

        override fun isInUse(): Boolean = false

        override fun install(
            verifiedTmp: File,
            version: String,
            sha256: String,
            meta: String?,
        ) = error("not used by registry validation")
    }
}
