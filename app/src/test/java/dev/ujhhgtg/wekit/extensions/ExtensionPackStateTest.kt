package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Installed
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.NotInstalled
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.VersionMismatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionPackStateTest {

    private val manifest = { version: String ->
        PackManifest("script-deps", version, "a".repeat(64), 0L)
    }

    @Test
    fun `no manifest means not installed`() {
        assertEquals(NotInstalled, classifyPackState(null, "20260818-abc"))
    }

    @Test
    fun `manifest equal to pin means installed`() {
        assertEquals(
            Installed("20260818-abc"),
            classifyPackState(manifest("20260818-abc"), "20260818-abc"),
        )
    }

    @Test
    fun `manifest differing from pin means version mismatch`() {
        assertEquals(
            VersionMismatch("20260101-old", "20260818-abc"),
            classifyPackState(manifest("20260101-old"), "20260818-abc"),
        )
    }
}
