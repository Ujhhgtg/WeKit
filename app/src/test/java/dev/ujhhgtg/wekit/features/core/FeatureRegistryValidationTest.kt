package dev.ujhhgtg.wekit.features.core

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureRegistryValidationTest {

    @Test
    fun validRegistryIsReturnedUnchanged() {
        val features = listOf(FakeFeature("first"), FakeFeature("second"))

        assertSame(features, validateFeatures(features))
    }

    @Test
    fun emptyTechnicalIdIsRejectedWithClassIdentity() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFeatures(listOf(FakeFeature("")))
        }

        assertTrue(error.message.orEmpty().contains("FakeFeature"))
    }

    @Test
    fun duplicateTechnicalIdsAreRejectedWithTheId() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFeatures(listOf(FakeFeature("duplicate"), FakeFeature("duplicate")))
        }

        assertTrue(error.message.orEmpty().contains("duplicate"))
    }

    @Test
    fun emptyCategoriesAreRejectedWithClassIdentity() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFeatures(listOf(FakeFeature("empty-categories", emptyList())))
        }

        assertTrue(error.message.orEmpty().contains("FakeFeature"))
    }

    @Test
    fun unknownCategoriesAreRejectedWithTheirValues() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFeatures(listOf(FakeFeature("unknown-category", listOf("unknown"))))
        }

        assertTrue(error.message.orEmpty().contains("unknown"))
    }

    private class FakeFeature(
        override val technicalId: String,
        override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT),
    ) : BaseFeature() {
        override val nameRes: Int = 0
    }
}
