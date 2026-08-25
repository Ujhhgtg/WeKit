package dev.ujhhgtg.wekit.dexkit.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class DexCacheCompatibilityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun legacyTechnicalIdsKeepTheirExistingCacheFileNames() {
        assertEquals(
            "朋友圈评论防撤回.json",
            DexCacheManager.cacheFileName("朋友圈评论防撤回"),
        )
    }

    @Test
    fun v1CacheIsInvalidatedWithoutRestoringItsDescriptor() {
        val restored = mutableListOf<String>()
        tempDir.resolve("Legacy.json").writeText(
            """{"methodHash":"old","timestamp":1,"shortKey":"Llegacy;"}""",
        )

        val result = restoreValidOwners(
            tempDir,
            listOf(currentOwner("owner.Legacy", "Legacy", "owner.Legacy#target", restored)),
        )

        val invalid = assertInstanceOf(
            DexCacheValidation.Invalid::class.java,
            result.invalidOwners.getValue("owner.Legacy"),
        )
        assertEquals(DexCacheInvalidReason.STALE_SCHEMA, invalid.reason)
        assertEquals(emptyList<String>(), restored)
    }
}
