package dev.ujhhgtg.wekit.features.core

import androidx.activity.ComponentActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeatureCategoryOrderingTest {

    @Test
    fun explicitOrdersPrecedeDefaultsAndOverrideFeatureTypePriority() {
        val features = listOf(
            TestSwitch("B"),
            TestClickable("D"),
            TestClickable("C", displayOrder = 20),
            TestSwitch("A", displayOrder = 10),
        )

        assertEquals(
            listOf("A", "C", "B", "D"),
            features.sortedWith(comparator()).map(BaseFeature::technicalId),
        )
    }

    @Test
    fun explicitOrderCanPlaceClickableBeforeSwitch() {
        val features = listOf(
            TestSwitch("A", displayOrder = 20),
            TestClickable("C", displayOrder = 10),
        )

        assertEquals(
            listOf("C", "A"),
            features.sortedWith(comparator()).map(BaseFeature::technicalId),
        )
    }

    @Test
    fun defaultsKeepSwitchesBeforeClickables() {
        val features = listOf(
            TestClickable("D"),
            TestSwitch("B"),
            TestClickable("C"),
            TestSwitch("A"),
        )

        assertEquals(
            listOf("A", "B", "C", "D"),
            features.sortedWith(comparator()).map(BaseFeature::technicalId),
        )
    }

    @Test
    fun equalExplicitOrdersUseNameThenTechnicalId() {
        val displayNames = mapOf(
            "technical-z" to "Alpha",
            "technical-b" to "Same",
            "technical-a" to "Same",
        )
        val features = listOf(
            TestSwitch("technical-b", displayOrder = 10),
            TestClickable("technical-z", displayOrder = 10),
            TestClickable("technical-a", displayOrder = 10),
        )

        assertEquals(
            listOf("technical-z", "technical-a", "technical-b"),
            features.sortedWith(
                featureCategoryComparator(
                    Comparator { first, second ->
                        displayNames.getValue(first.technicalId)
                            .compareTo(displayNames.getValue(second.technicalId))
                    },
                ),
            ).map(BaseFeature::technicalId),
        )
    }

    private fun comparator(): Comparator<BaseFeature> =
        featureCategoryComparator(compareBy(BaseFeature::technicalId))

    private class TestSwitch(
        override val technicalId: String,
        override val displayOrder: Int? = null,
    ) : SwitchFeature() {
        override val nameRes: Int = 0
        override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    }

    private class TestClickable(
        override val technicalId: String,
        override val displayOrder: Int? = null,
    ) : ClickableFeature() {
        override val nameRes: Int = 0
        override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)

        override fun onClick(context: ComponentActivity) = Unit
    }
}
