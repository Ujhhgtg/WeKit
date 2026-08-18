package dev.ujhhgtg.wekit.pet.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
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

    // Decode the atlas once per pet id.
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, entry.assetDir, definition.spritesheetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val path = "${entry.assetDir}/${definition.spritesheetPath}"
                HostInfo.application.assets.open(path).use { input ->
                    BitmapFactory.decodeStream(input)
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
            value = frame
            val track = definition.tracks[frame.animation]
            val duration = track?.durations?.getOrNull(frame.frameIndex)?.toLong() ?: 100L
            delay(duration.coerceIn(16L, 1000L))
        }
    }

    val image = bitmap ?: return
    val cell = definition.cell
    val col = definition.tracks[display.animation]?.frames?.getOrNull(display.frameIndex) ?: 0
    val row = display.animation.row

    val aspect = cell.width.toFloat() / cell.height.toFloat()

    Canvas(
        modifier = modifier.size(width = height * aspect, height = height),
    ) {
        drawImage(
            image = image.asImageBitmap(),
            srcOffset = IntOffset(col * cell.width, row * cell.height),
            srcSize = IntSize(cell.width, cell.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
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
