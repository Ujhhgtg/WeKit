package dev.ujhhgtg.wekit.pet.core

/**
 * Pet animation contract — ported 1:1 from the dsh-pet / hatch-pet contract.
 * One pet is a spritesheet atlas (default 8 columns x 9 rows of 192x208
 * cells) whose 9 rows are the fixed animation states below, in this order.
 * Pure Kotlin, no Android dependency, so the whole core layer is unit-testable
 * on the JVM.
 */

/** The Codex-compatible 9-state animation contract (spritesheet rows). */
enum class PetAnimation(val id: String, val row: Int) {
    IDLE("idle", 0),
    RUNNING_RIGHT("running-right", 1),
    RUNNING_LEFT("running-left", 2),
    WAVING("waving", 3),
    JUMPING("jumping", 4),
    FAILED("failed", 5),
    WAITING("waiting", 6),
    RUNNING("running", 7),
    REVIEW("review", 8);

    companion object {
        val ROW_ORDER: List<PetAnimation> = entries
        fun fromId(id: String): PetAnimation? = entries.firstOrNull { it.id == id }
    }
}

/** Activity phases understood by the pet host. */
enum class ActivityPhase(val id: String) {
    IDLE("idle"),
    WAITING("waiting"),
    THINKING("thinking"),
    TOOL("tool"),
    REVIEW("review"),
    DONE("done"),
    FAILED("failed");

    companion object {
        fun fromId(id: String): ActivityPhase? = entries.firstOrNull { it.id == id }
    }
}

/** Atlas cell size in px. */
data class PetCell(val width: Int, val height: Int)

/** Default per-track rhythm (hatch-pet contract table). */
data class PetTrackPattern(
    val durations: List<Int>,
    val loop: Boolean,
    val fallback: PetAnimation? = null,
)

/** One resolved animation track (frames + durations + loop/fallback). */
data class PetTrackDef(
    val frames: List<Int>,
    val durations: List<Int>,
    val loop: Boolean,
    val fallback: PetAnimation? = null,
)

// ---------------------------------------------------------------------------
// Contract constants (hatch-pet / Codex).
// ---------------------------------------------------------------------------

/** Fixed row order of the 9-state animation contract. */
val PET_ROW_ORDER: List<PetAnimation> = PetAnimation.ROW_ORDER

/** Atlas cell size in px (Codex/hatch-pet contract). */
val DEFAULT_PET_CELL = PetCell(width = 192, height = 208)

/** Columns per row (max frames per track). */
const val DEFAULT_PET_COLUMNS = 8

/** Rows in the atlas (fixed by the animation contract). */
const val DEFAULT_PET_ROW_COUNT = 9

/** Per-row used-column counts from the hatch-pet contract table. */
val DEFAULT_FRAME_COUNTS: List<Int> = listOf(6, 8, 8, 4, 5, 8, 6, 6, 6)

/** Default per-track rhythm (hatch-pet contract table). */
val DEFAULT_TRACK_PATTERNS: Map<PetAnimation, PetTrackPattern> = mapOf(
    PetAnimation.IDLE to PetTrackPattern(listOf(280, 110, 110, 140, 140, 320), loop = true),
    PetAnimation.RUNNING_RIGHT to PetTrackPattern(listOf(120, 120, 120, 120, 120, 120, 120, 220), loop = true),
    PetAnimation.RUNNING_LEFT to PetTrackPattern(listOf(120, 120, 120, 120, 120, 120, 120, 220), loop = true),
    PetAnimation.WAVING to PetTrackPattern(listOf(140, 140, 140, 280), loop = true),
    PetAnimation.JUMPING to PetTrackPattern(listOf(140, 140, 140, 140, 280), loop = false, fallback = PetAnimation.IDLE),
    PetAnimation.FAILED to PetTrackPattern(listOf(140, 140, 140, 140, 140, 140, 140, 240), loop = false, fallback = PetAnimation.IDLE),
    PetAnimation.WAITING to PetTrackPattern(listOf(150, 150, 150, 150, 150, 260), loop = true),
    PetAnimation.RUNNING to PetTrackPattern(listOf(120, 120, 120, 120, 120, 220), loop = true),
    PetAnimation.REVIEW to PetTrackPattern(listOf(150, 150, 150, 150, 150, 280), loop = true),
)

/** Map one activity phase onto the animation contract. */
fun animationForPhase(phase: ActivityPhase): PetAnimation = when (phase) {
    ActivityPhase.THINKING -> PetAnimation.RUNNING
    ActivityPhase.TOOL -> PetAnimation.RUNNING_RIGHT
    ActivityPhase.REVIEW -> PetAnimation.REVIEW
    ActivityPhase.WAITING -> PetAnimation.WAITING
    ActivityPhase.DONE -> PetAnimation.JUMPING
    ActivityPhase.FAILED -> PetAnimation.FAILED
    ActivityPhase.IDLE -> PetAnimation.IDLE
}

/** The spritesheet row index for one animation track. */
fun rowOf(animation: PetAnimation): Int = animation.row
