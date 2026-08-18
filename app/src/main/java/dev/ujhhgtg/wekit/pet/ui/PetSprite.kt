package dev.ujhhgtg.wekit.pet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.ujhhgtg.wekit.pet.core.ActivityPhase
import dev.ujhhgtg.wekit.pet.core.DisplayFrame
import dev.ujhhgtg.wekit.pet.core.PetAnimation
import dev.ujhhgtg.wekit.pet.core.PetDefinition
import dev.ujhhgtg.wekit.pet.core.PetEntry
import dev.ujhhgtg.wekit.pet.core.animationForPhase
import dev.ujhhgtg.wekit.pet.core.displayFrameAt
import dev.ujhhgtg.wekit.pet.core.sequenceFrameAt
import dev.ujhhgtg.wekit.utils.HostInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Renders one pet from its spritesheet atlas, phase-driven. A manifest-declared
 * scene [ActivityPhase] sequence wins; otherwise the phase maps onto a single
 * animation track that plays with its loop/fallback rhythm. The atlas is decoded
 * once (keyed by pet id) on a background thread.
 */
@Composable
fun PetSprite(
    entry: PetEntry,
    phase: ActivityPhase,
    animationStartedAt: Long,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val definition = entry.definition

    // Decode the atlas once per pet id (as an ImageBitmap so the expensive
    // Bitmap -> ImageBitmap wrap happens exactly once, not on every recomposition).
    val image by produceState<ImageBitmap?>(initialValue = null, entry.assetDir, definition.spritesheetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val path = "${entry.assetDir}/${definition.spritesheetPath}"
                HostInfo.application.assets.open(path).use { input ->
                    // inScaled=false keeps the bitmap at the asset's native pixel size (the
                    // framework would otherwise scale it by the display density, which both
                    // wastes memory and smears the already-small atlas cells). ARGB_8888 gives
                    // the smoothest interpolation when we upscale for larger display sizes.
                    val opts = BitmapFactory.Options().apply {
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    // Advance frames following the sequence/track rhythm.
    val display by produceState(
        initialValue = computeDisplayFrame(definition, phase, 0L),
        phase,
        animationStartedAt,
    ) {
        while (true) {
            val elapsed = (System.currentTimeMillis() - animationStartedAt).coerceAtLeast(0L)
            val frame = computeDisplayFrame(definition, phase, elapsed)
            // Only write when the frame advances. Writing an equal data class on every tick
            // still pings the snapshot machinery; for a long-lived overlay this churn is
            // measurable, and skipping it is what keeps the pet from slowly degrading.
            if (frame != value) value = frame
            val track = definition.tracks[frame.animation]
            val duration = track?.durations?.getOrNull(frame.frameIndex)?.toLong() ?: 100L
            delay(duration.coerceIn(16L, 1000L))
        }
    }

    val img = image ?: return
    val cell = definition.cell
    val col = definition.tracks[display.animation]?.frames?.getOrNull(display.frameIndex) ?: 0
    val row = display.animation.row

    val aspect = cell.width.toFloat() / cell.height.toFloat()

    Canvas(
        modifier = modifier.size(width = height * aspect, height = height),
    ) {
        val w = size.width
        val h = size.height

        // Subtle "breathing" scale while idle — a low-cost 3D-like cue that the
        // pet is alive, anchored at the feet so it gently rises and settles.
        val breath = if (phase == ActivityPhase.IDLE) {
            val t = (System.currentTimeMillis() % 4000L).toFloat() / 4000f * (2f * PI.toFloat())
            1f + 0.025f * sin(t)
        } else {
            1f
        }

        // Soft ground shadow for a floating / lifted feel.
        drawOval(
            color = Color.Black.copy(alpha = 0.16f),
            topLeft = Offset(w * (0.5f - 0.30f * breath), h * 0.93f),
            size = Size(w * 0.60f * breath, h * 0.05f),
        )

        val dstW = w * breath
        val dstH = h * breath
        drawImage(
            image = img,
            srcOffset = IntOffset(col * cell.width, row * cell.height),
            srcSize = IntSize(cell.width, cell.height),
            dstOffset = IntOffset(((w - dstW) / 2f).toInt(), (h - dstH).toInt()),
            dstSize = IntSize(dstW.toInt(), dstH.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Resolve which animation frame to show for a phase after [elapsedMs] milliseconds. */
private fun computeDisplayFrame(
    definition: PetDefinition,
    phase: ActivityPhase,
    elapsedMs: Long,
): DisplayFrame {
    val sequence = definition.sequences?.get(phase)
    if (sequence != null && sequence.isNotEmpty()) {
        val seq = sequenceFrameAt(sequence, definition.tracks, elapsedMs)
        return DisplayFrame(seq.animation, seq.frameIndex)
    }
    val animation = animationForPhase(phase)
    return displayFrameAt(definition.tracks, animation, 0L, elapsedMs)
}
