package dev.ujhhgtg.wekit.pet.core

/**
 * Pure timing helpers for manifest-defined scene animation sequences.
 * Ported 1:1 from dsh-pet client/sequences.ts.
 */

data class SequenceFrame(
    val animation: PetAnimation,
    val frameIndex: Int,
)

/** Resolve the active track and frame after elapsed milliseconds of a looping sequence. */
fun sequenceFrameAt(
    sequence: List<PetAnimation>,
    tracks: Map<PetAnimation, PetTrackDef>,
    elapsedMs: Long,
): SequenceFrame {
    val itemDurations = sequence.map { anim ->
        tracks[anim]!!.durations.sumOf { it.toLong() }
    }
    val sequenceDuration = itemDurations.sumOf { it }
    var offset = 0L.coerceAtLeast(elapsedMs) % sequenceDuration
    var itemIndex = 0
    while (itemIndex < sequence.size - 1 && offset >= itemDurations[itemIndex]) {
        offset -= itemDurations[itemIndex]
        itemIndex += 1
    }
    val animation = sequence[itemIndex]
    val track = tracks[animation]!!
    var frameIndex = 0
    while (frameIndex < track.frames.size - 1 && offset >= track.durations[frameIndex]) {
        offset -= track.durations[frameIndex]
        frameIndex += 1
    }
    return SequenceFrame(animation, frameIndex)
}
