package dev.ujhhgtg.wekit.ui.content.nukex

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun NukeColorSwatch(
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = NukeSquircleShape(10.dp)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val clickModifier = if (onClick == null) {
            Modifier
        } else {
            Modifier.nukeJellyClickable(onClick = { onClick() })
        }
        Box(
            Modifier
                .size(34.dp)
                .then(clickModifier)
                .clip(shape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        NukeTheme.colors.textPrimary
                    } else {
                        NukeTheme.colors.border
                    },
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                val luminance =
                    color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f
                NukeGlyph(
                    kind = NukeGlyphKind.Check,
                    color = if (luminance > 0.58f) {
                        Color.Black.copy(alpha = 0.72f)
                    } else {
                        Color.White
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun NukeSaturationValuePalette(
    hue: Float,
    saturation: Float,
    value: Float,
    onChanged: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = NukeSquircleShape(12.dp)
    val hueColor = Color(
        AndroidColor.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), 1f, 1f))
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(2.15f)
            .clip(shape)
            .border(1.dp, NukeTheme.colors.border, shape)
            .pointerInput(hue) {
                fun update(position: Offset) {
                    onChanged(
                        (position.x / size.width).coerceIn(0f, 1f),
                        (1f - position.y / size.height).coerceIn(0f, 1f),
                    )
                }
                detectDragGestures(
                    onDragStart = ::update,
                    onDrag = { change, _ ->
                        update(change.position)
                        change.consume()
                    },
                )
            }
            .pointerInput(hue) {
                detectTapGestures { position ->
                    onChanged(
                        (position.x / size.width).coerceIn(0f, 1f),
                        (1f - position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(
            saturation.coerceIn(0f, 1f) * size.width,
            (1f - value.coerceIn(0f, 1f)) * size.height,
        )
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = marker,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.28f),
            radius = 8.dp.toPx(),
            center = marker,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
fun NukeHueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        Color.Red,
        Color.Yellow,
        Color.Green,
        Color.Cyan,
        Color.Blue,
        Color.Magenta,
        Color.Red,
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(CircleShape)
            .border(1.dp, NukeTheme.colors.border, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        onHueChange((position.x / size.width).coerceIn(0f, 1f) * 360f)
                    },
                    onDrag = { change, _ ->
                        onHueChange((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                        change.consume()
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    onHueChange((position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            }
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(colors),
            cornerRadius = CornerRadius(size.height / 2f),
        )
        val markerX = (hue / 360f).coerceIn(0f, 1f) * size.width
        drawLine(
            color = Color.White,
            start = Offset(markerX, 4.dp.toPx()),
            end = Offset(markerX, size.height - 4.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

fun Color.toNukeHex(): String =
    "#%02X%02X%02X".format(
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255),
    )

fun String.parseNukeColor(): Color? {
    if (!matches(Regex("^#[0-9A-Fa-f]{6}$"))) return null
    return runCatching {
        Color(AndroidColor.parseColor(this))
    }.getOrNull()
}

fun Color.toNukeHsv(): FloatArray =
    FloatArray(3).also { hsv ->
        AndroidColor.RGBToHSV(
            (red * 255f).roundToInt(),
            (green * 255f).roundToInt(),
            (blue * 255f).roundToInt(),
            hsv,
        )
    }
