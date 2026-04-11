package dev.ujhhgtg.wekit.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val GitHubIcon
    get() = ImageVector.Builder(
        name = "GitHubIcon",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12.0f, 2.0f)
            arcTo(10.126f, 10.126f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 2.0f, 12.248f)
            arcTo(10.257f, 10.257f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 8.84f, 21.984f)
            curveToRelative(0.5f, 0.082f, 0.66f, -0.236f, 0.66f, -0.512f)
            verticalLineTo(19.74f)
            curveToRelative(-2.77f, 0.615f, -3.36f, -1.373f, -3.36f, -1.373f)
            arcTo(2.741f, 2.741f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 5.03f, 16.86f)
            curveToRelative(-0.91f, -0.635f, 0.07f, -0.615f, 0.07f, -0.615f)
            arcTo(2.109f, 2.109f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.63f, 17.3f)
            arcTo(2.118f, 2.118f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 9.54f, 18.151f)
            arcTo(2.235f, 2.235f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 10.17f, 16.778f)
            curveToRelative(-2.22f, -0.256f, -4.55f, -1.138f, -4.55f, -5.042f)
            arcTo(4.025f, 4.025f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.65f, 8.959f)
            arcTo(3.75f, 3.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 6.75f, 6.253f)
            reflectiveCurveTo(7.59f, 5.976f, 9.5f, 7.3f)
            arcTo(9.409f, 9.409f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 14.5f, 7.3f)
            curveToRelative(1.91f, -1.322f, 2.75f, -1.045f, 2.75f, -1.045f)
            arcTo(3.75f, 3.75f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 17.35f, 8.964f)
            arcTo(4.025f, 4.025f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 18.38f, 11.741f)
            curveToRelative(0.0f, 3.915f, -2.34f, 4.776f, -4.57f, 5.032f)
            arcTo(2.477f, 2.477f, 0.0f, isMoreThanHalf = false, isPositiveArc = true, 14.5f, 18.673f)
            verticalLineTo(21.481f)
            curveToRelative(0.0f, 0.277f, 0.16f, 0.6f, 0.67f, 0.512f)
            arcTo(10.259f, 10.259f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 22.0f, 12.248f)
            arcTo(10.126f, 10.126f, 0.0f, isMoreThanHalf = false, isPositiveArc = false, 12.0f, 2.0f)
            close()
        }
    }.build()

val TelegramIcon
    get() = ImageVector.Builder(
        name = "TelegramIcon",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 1024.0f,
        viewportHeight = 1024.0f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(679.4f, 746.9f)
            lineToRelative(84.0f, -396.0f)
            curveToRelative(7.4f, -34.9f, -12.6f, -48.6f, -35.4f, -40.0f)
            lineToRelative(-493.7f, 190.3f)
            curveToRelative(-33.7f, 13.1f, -33.1f, 32.0f, -5.7f, 40.6f)
            lineToRelative(126.3f, 39.4f)
            lineToRelative(293.2f, -184.6f)
            curveToRelative(13.7f, -9.1f, 26.3f, -4.0f, 16.0f, 5.2f)
            lineToRelative(-237.1f, 214.3f)
            lineToRelative(-9.1f, 130.3f)
            curveToRelative(13.1f, 0.0f, 18.9f, -5.7f, 25.7f, -12.6f)
            lineToRelative(61.7f, -59.4f)
            lineToRelative(128.0f, 94.3f)
            curveToRelative(23.4f, 13.1f, 40.0f, 6.3f, 46.3f, -21.7f)
            close()
            moveTo(1024.0f, 512.0f)
            curveToRelative(0.0f, 282.8f, -229.2f, 512.0f, -512.0f, 512.0f)
            reflectiveCurveTo(0.0f, 794.8f, 0.0f, 512.0f)
            reflectiveCurveTo(229.2f, 0.0f, 512.0f, 0.0f)
            reflectiveCurveToRelative(512.0f, 229.2f, 512.0f, 512.0f)
            close()
        }
    }.build()
