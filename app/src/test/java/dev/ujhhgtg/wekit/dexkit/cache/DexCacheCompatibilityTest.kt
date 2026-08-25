package dev.ujhhgtg.wekit.dexkit.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DexCacheCompatibilityTest {
    @Test
    fun legacyTechnicalIdsKeepTheirExistingCacheFileNames() {
        assertEquals(
            "朋友圈评论防撤回.json",
            DexCacheManager.cacheFileName("朋友圈评论防撤回"),
        )
    }
}
