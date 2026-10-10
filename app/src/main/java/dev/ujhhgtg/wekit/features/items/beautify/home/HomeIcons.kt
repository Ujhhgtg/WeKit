package dev.ujhhgtg.wekit.features.items.beautify.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// follows the design of YumeBox
object HomeIcons {
    val Contacts: ImageVector = railIcon("Home.Contacts") {
        moveTo(7f, 3f)
        horizontalLineTo(18f)
        curveTo(19.1f, 3f, 20f, 3.9f, 20f, 5f)
        verticalLineTo(19f)
        curveTo(20f, 20.1f, 19.1f, 21f, 18f, 21f)
        horizontalLineTo(7f)
        curveTo(5.9f, 21f, 5f, 20.1f, 5f, 19f)
        verticalLineTo(5f)
        curveTo(5f, 3.9f, 5.9f, 3f, 7f, 3f)
        close()

        moveTo(3f, 7f)
        horizontalLineTo(6f)
        moveTo(3f, 12f)
        horizontalLineTo(6f)
        moveTo(3f, 17f)
        horizontalLineTo(6f)

        moveTo(15f, 9f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 10f, y1 = 9f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 9f)
        close()

        moveTo(8.5f, 17f)
        verticalLineTo(16.5f)
        curveTo(8.5f, 15.4f, 9.4f, 14.5f, 10.5f, 14.5f)
        horizontalLineTo(14.5f)
        curveTo(15.6f, 14.5f, 16.5f, 15.4f, 16.5f, 16.5f)
        verticalLineTo(17f)
    }

    val Discover: ImageVector = railIcon("Home.Discover") {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 12f)
        close()

        moveTo(15.5f, 8.5f)
        lineTo(13.5f, 13.5f)
        lineTo(8.5f, 15.5f)
        lineTo(10.5f, 10.5f)
        close()
    }

    val Profile: ImageVector = railIcon("Home.Profile") {
        moveTo(15.5f, 7.5f)
        arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 8.5f, y1 = 7.5f)
        arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.5f, y1 = 7.5f)
        close()

        moveTo(5f, 21f)
        verticalLineTo(19f)
        curveTo(5f, 16.8f, 6.8f, 15f, 9f, 15f)
        horizontalLineTo(15f)
        curveTo(17.2f, 15f, 19f, 16.8f, 19f, 19f)
        verticalLineTo(21f)
    }

    private fun railIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
