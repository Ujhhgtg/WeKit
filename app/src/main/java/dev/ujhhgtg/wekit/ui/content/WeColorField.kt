package dev.ujhhgtg.wekit.ui.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The module's single color input: a hex text field that flags unparseable input, with a preview
 * swatch that opens [WeColorPickerDialog] when tapped.
 *
 * Accepts anything [toColorInt] understands (`#RRGGBB`, `#AARRGGBB`, `black`, `gray`, …) so existing
 * stored values keep working; picking a color always writes back uppercase `#AARRGGBB`.
 */
@Composable
fun WeColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val parsedColor = remember(value) {
        value.takeIf { it.isNotBlank() }?.let { runCatching { it.toColorInt() }.getOrNull() }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotBlank() && parsedColor == null,
        trailingIcon = {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(if (parsedColor != null) Modifier.checkerboard(4.dp) else Modifier)
                    .background(
                        parsedColor?.let(::ComposeColor) ?: MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = enabled) {
                        showComposeDialog(context) {
                            WeColorPickerDialog(
                                initial = parsedColor ?: AndroidColor.BLACK,
                                onDismiss = onDismiss,
                                onConfirm = { picked ->
                                    onValueChange(formatArgbHex(picked))
                                    onDismiss()
                                },
                            )
                        }
                    }
            )
        },
    )
}

/**
 * Hue/saturation/value/alpha color picker, for use inside a [showComposeDialog].
 *
 * Works in HSV rather than packed ARGB so that dragging value or saturation to zero doesn't collapse
 * the other components — a round trip through RGB would lose the hue of any black or gray.
 */
@Composable
fun WeColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }

    var hue by remember { mutableFloatStateOf(initialHsv[0] / 360f) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(AndroidColor.alpha(initial) / 255f) }
    var hexText by remember { mutableStateOf(formatArgbHex(initial)) }

    val current = argbOf(hue, saturation, brightness, alpha)

    /** Applies a slider edit and refreshes the hex field to match. */
    fun onSliderChange(apply: () -> Unit) {
        apply()
        hexText = formatArgbHex(argbOf(hue, saturation, brightness, alpha))
    }

    val hexError = runCatching { hexText.toColorInt() }.isFailure

    AlertDialogContent(
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .checkerboard()
                        .background(ComposeColor(current))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                )

                ColorSlider(
                    label = stringResource(R.string.color_picker_hue),
                    value = hue,
                    onValueChange = { next -> onSliderChange { hue = next } },
                    trackBrush = Brush.horizontalGradient(
                        List(HUE_STOPS + 1) { ComposeColor(argbOf(it / HUE_STOPS.toFloat(), 1f, 1f, 1f)) }
                    ),
                )
                ColorSlider(
                    label = stringResource(R.string.color_picker_saturation),
                    value = saturation,
                    onValueChange = { next -> onSliderChange { saturation = next } },
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            ComposeColor(argbOf(hue, 0f, brightness, 1f)),
                            ComposeColor(argbOf(hue, 1f, brightness, 1f)),
                        )
                    ),
                )
                ColorSlider(
                    label = stringResource(R.string.color_picker_value),
                    value = brightness,
                    onValueChange = { next -> onSliderChange { brightness = next } },
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            ComposeColor(argbOf(hue, saturation, 0f, 1f)),
                            ComposeColor(argbOf(hue, saturation, 1f, 1f)),
                        )
                    ),
                )
                ColorSlider(
                    label = stringResource(R.string.color_picker_opacity),
                    value = alpha,
                    onValueChange = { next -> onSliderChange { alpha = next } },
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            ComposeColor(argbOf(hue, saturation, brightness, 0f)),
                            ComposeColor(argbOf(hue, saturation, brightness, 1f)),
                        )
                    ),
                    checkerboard = true,
                )

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { next ->
                        hexText = next
                        runCatching { next.toColorInt() }.getOrNull()?.let { parsed ->
                            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(parsed, it) }
                            hue = hsv[0] / 360f
                            saturation = hsv[1]
                            brightness = hsv[2]
                            alpha = AndroidColor.alpha(parsed) / 255f
                        }
                    },
                    label = { Text(stringResource(R.string.color_picker_hex_value)) },
                    singleLine = true,
                    isError = hexError,
                    supportingText = if (hexError) {
                        { Text(stringResource(R.string.color_picker_invalid_argb)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        confirmButton = {
            Button(onClick = { onConfirm(current) }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
    )
}

/**
 * A 0..1 slider drawn over [trackBrush]. Hand-rolled rather than an `M3 Slider` with an overridden
 * track because the thumb has to sit on top of an arbitrary gradient, and because a press anywhere
 * on the track should jump the value there without waiting for drag slop.
 */
@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    trackBrush: Brush,
    modifier: Modifier = Modifier,
    checkerboard: Boolean = false,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val outline = MaterialTheme.colorScheme.outline
    val pill = RoundedCornerShape(percent = 50)

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(SLIDER_HEIGHT)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(widthPx) {
                    val thumbRadius = THUMB_RADIUS.toPx()
                    val span = widthPx - thumbRadius * 2

                    fun fractionAt(x: Float) =
                        if (span <= 0f) 0f else ((x - thumbRadius) / span).coerceIn(0f, 1f)

                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onValueChange(fractionAt(down.position.x))
                            drag(down.id) { change ->
                                onValueChange(fractionAt(change.position.x))
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .clip(pill)
                    .then(if (checkerboard) Modifier.checkerboard(4.dp) else Modifier)
                    .background(trackBrush)
                    .border(1.dp, outline, pill)
            )
            Canvas(Modifier.matchParentSize()) {
                val thumbRadius = THUMB_RADIUS.toPx()
                val centerX = thumbRadius + value.coerceIn(0f, 1f) * (size.width - thumbRadius * 2)
                val center = Offset(centerX, size.height / 2f)
                drawCircle(ComposeColor.White, thumbRadius, center)
                drawCircle(outline, thumbRadius, center, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** Draws an alpha-indicating checkerboard behind the composable's own content. */
fun Modifier.checkerboard(cell: Dp = CHECKER_CELL): Modifier =
    drawBehind { drawCheckerboard(cell.toPx(), size) }

private fun DrawScope.drawCheckerboard(cellPx: Float, area: Size) {
    if (cellPx <= 0f) return
    drawRect(CHECKER_LIGHT, size = area)
    val columns = ceil(area.width / cellPx).toInt()
    val rows = ceil(area.height / cellPx).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 == 0) continue
            val left = column * cellPx
            val top = row * cellPx
            drawRect(
                color = CHECKER_DARK,
                topLeft = Offset(left, top),
                size = Size(min(cellPx, area.width - left), min(cellPx, area.height - top)),
            )
        }
    }
}

/** Packs normalized HSVA components into an ARGB int. */
private fun argbOf(hue: Float, saturation: Float, brightness: Float, alpha: Float): Int =
    AndroidColor.HSVToColor(
        (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        floatArrayOf(hue.coerceIn(0f, 1f) * 360f, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)),
    )

/** The module's canonical on-disk color notation. */
fun formatArgbHex(argb: Int): String = "#%08X".format(argb)

private const val HUE_STOPS = 12
private val SLIDER_HEIGHT = 32.dp
private val TRACK_HEIGHT = 16.dp
private val THUMB_RADIUS = 10.dp
private val CHECKER_CELL = 6.dp
private val CHECKER_LIGHT = ComposeColor(0xFFF2F2F2)
private val CHECKER_DARK = ComposeColor(0xFFC8C8C8)
