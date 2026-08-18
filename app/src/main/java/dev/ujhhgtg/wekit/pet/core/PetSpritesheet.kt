package dev.ujhhgtg.wekit.pet.core

/**
 * Spritesheet geometry helpers — ported 1:1 from dsh-pet client/spritesheet.ts.
 * Places frame cells and guards track lengths. (framePosition returns pixel
 * offsets of a frame's top-left in the atlas, unlike the CSS background-position
 * variant which used negative offsets — the Compose renderer uses these directly.)
 */

/** Row index of one animation track (the fixed 9-row contract). */
fun rowOfTrack(animation: PetAnimation): Int = rowOf(animation)

/** Pixel position (top-left) of one frame cell within the full-size atlas. */
data class FramePosition(val x: Int, val y: Int)

fun framePosition(
    cell: PetCell,
    row: Int,
    col: Int,
): FramePosition = FramePosition(x = col * cell.width, y = row * cell.height)

/** Total duration of one track, ms. */
fun trackDuration(track: PetTrackDef): Long = track.durations.sumOf { it.toLong() }

/**
 * Trim a track to the actual frame count of its row (last-line guard against a
 * definition whose row count disagrees with its track table). A row with 0
 * detected frames degrades to the first frame so the pet never renders blank.
 */
fun trimTrack(track: PetTrackDef, frameCount: Int): PetTrackDef {
    val n = 1.coerceAtLeast(frameCount.coerceAtMost(track.frames.size).coerceAtMost(track.durations.size))
    return PetTrackDef(
        frames = track.frames.take(n),
        durations = track.durations.take(n),
        loop = track.loop,
        fallback = track.fallback,
    )
}

/** The active animation + frame index at an elapsed wall-clock instant. */
data class DisplayFrame(val animation: PetAnimation, val frameIndex: Int)

/**
 * Resolve which frame to display for an animation track that started at
 * [startedAt]. Looping tracks cycle within their own frame table; a
 * non-looping track plays once then follows its [PetTrackDef.fallback] chain
 * (e.g. `jumping` → `idle`). The fallback chain is bounded to avoid cycles.
 */
fun displayFrameAt(
    tracks: Map<PetAnimation, PetTrackDef>,
    animation: PetAnimation,
    startedAt: Long,
    nowMs: Long,
): DisplayFrame {
    var anim = animation
    var track = tracks[anim] ?: return DisplayFrame(animation, 0)
    var elapsed = (nowMs - startedAt).coerceAtLeast(0L)

    var hops = 0
    while (!track.loop && elapsed >= trackDuration(track) && hops < 4) {
        val fallback = track.fallback ?: break
        elapsed -= trackDuration(track)
        anim = fallback
        track = tracks[anim] ?: return DisplayFrame(animation, 0)
        hops++
    }

    if (track.loop) {
        val total = trackDuration(track)
        var offset = if (total > 0) elapsed % total else 0L
        var idx = 0
        while (idx < track.frames.size - 1 && offset >= track.durations[idx]) {
            offset -= track.durations[idx]
            idx++
        }
        return DisplayFrame(anim, idx)
    }

    // Non-looping, still playing: advance in order and hold the last frame.
    var offset = elapsed
    var idx = 0
    while (idx < track.frames.size - 1 && offset >= track.durations[idx]) {
        offset -= track.durations[idx]
        idx++
    }
    return DisplayFrame(anim, idx.coerceAtMost(track.frames.size - 1))
}
