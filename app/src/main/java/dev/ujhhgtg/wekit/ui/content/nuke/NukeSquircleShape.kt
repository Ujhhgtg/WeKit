package dev.ujhhgtg.wekit.ui.content.nuke

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

class NukeSquircleShape(
    private val radius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val requestedRadius = with(density) { radius.toPx() }.coerceAtLeast(0f)
        val smoothness = 1.35f.coerceIn(1f, 1.6f)
        val halfShortEdge = min(size.width, size.height) / 2f
        val effectiveRadius = min(requestedRadius * smoothness, halfShortEdge)
        val controlDistance = min(
            ((smoothness - 1f) * 0.18f + 0.52f) * requestedRadius,
            effectiveRadius,
        )

        return Outline.Generic(
            Path().apply {
                moveTo(effectiveRadius, 0f)
                lineTo(size.width - effectiveRadius, 0f)
                cubicTo(
                    size.width - controlDistance,
                    0f,
                    size.width,
                    controlDistance,
                    size.width,
                    effectiveRadius,
                )
                lineTo(size.width, size.height - effectiveRadius)
                cubicTo(
                    size.width,
                    size.height - controlDistance,
                    size.width - controlDistance,
                    size.height,
                    size.width - effectiveRadius,
                    size.height,
                )
                lineTo(effectiveRadius, size.height)
                cubicTo(
                    controlDistance,
                    size.height,
                    0f,
                    size.height - controlDistance,
                    0f,
                    size.height - effectiveRadius,
                )
                lineTo(0f, effectiveRadius)
                cubicTo(
                    0f,
                    controlDistance,
                    controlDistance,
                    0f,
                    effectiveRadius,
                    0f,
                )
                close()
            }
        )
    }
}
