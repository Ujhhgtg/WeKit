package dev.ujhhgtg.wekit.features.items.chat

import kotlin.math.abs

/**
 * Arbitrates one conversation-list gesture without consuming its events.
 *
 * A horizontal candidate stays pending until the host has dispatched the deciding MOVE to its
 * children. The coordinator then calls [claimGroup], unless a child has claimed that stream first.
 */
class ConversationGroupSwipeState(private val touchSlopPx: Float) {
    enum class Owner {
        IDLE,
        PENDING,
        NATIVE_VERTICAL,
        OUTER,
        CHILD,
        GROUP,
    }

    enum class Direction(val dragSign: Float) {
        PREVIOUS(1f),
        NEXT(-1f),
    }

    data class Settlement(val direction: Direction, val commit: Boolean)

    var owner: Owner = Owner.IDLE
        private set

    var candidateDirection: Direction? = null
        private set

    var progress: Float = 0f
        private set

    private var startX = 0f
    private var startY = 0f
    private var widthPx = 1f
    private var canGoPrevious = false
    private var canGoNext = false
    private var lastMotionX = 0f
    private var lastMotionTimeMs = 0L
    private var velocityPxPerMs = 0f

    fun onDown(
        x: Float,
        y: Float,
        widthPx: Float,
        timeMs: Long,
        canGoPrevious: Boolean,
        canGoNext: Boolean,
        eligible: Boolean = true,
    ) {
        require(widthPx > 0f)
        startX = x
        startY = y
        this.widthPx = widthPx
        this.canGoPrevious = canGoPrevious
        this.canGoNext = canGoNext
        lastMotionX = x
        lastMotionTimeMs = timeMs
        velocityPxPerMs = 0f
        candidateDirection = null
        progress = 0f
        owner = if (eligible) Owner.PENDING else Owner.OUTER
    }

    fun onMove(x: Float, y: Float, timeMs: Long): Direction? {
        if (owner != Owner.PENDING && owner != Owner.GROUP) return null

        // A coordinator may observe the same MOVE before and after child dispatch. Sampling it
        // twice must not replace a real velocity with zero or divide by its zero time interval.
        val elapsedMs = timeMs - lastMotionTimeMs
        if (elapsedMs > 0) {
            velocityPxPerMs = (x - lastMotionX) / elapsedMs
            lastMotionX = x
            lastMotionTimeMs = timeMs
        }

        val dx = x - startX
        val dy = y - startY
        if (candidateDirection == null) {
            val absDx = abs(dx)
            val absDy = abs(dy)
            when {
                absDy > touchSlopPx && absDy >= absDx -> {
                    owner = Owner.NATIVE_VERTICAL
                    return null
                }

                absDx > touchSlopPx && absDx >= absDy * HORIZONTAL_DOMINANCE -> {
                    val direction = if (dx > 0f) Direction.PREVIOUS else Direction.NEXT
                    val hasNeighbor = when (direction) {
                        Direction.PREVIOUS -> canGoPrevious
                        Direction.NEXT -> canGoNext
                    }
                    if (!hasNeighbor) {
                        owner = Owner.OUTER
                        return null
                    }
                    candidateDirection = direction
                }

                else -> return null
            }
        }

        // Reversing an owned drag returns to the starting page, never to its opposite neighbor.
        progress = (dx * candidateDirection!!.dragSign / widthPx).coerceIn(0f, 1f)
        return candidateDirection
    }

    fun onChildClaimed() {
        if (owner == Owner.IDLE || owner == Owner.GROUP) return
        owner = Owner.CHILD
        candidateDirection = null
        progress = 0f
    }

    fun claimGroup(): Boolean {
        if (owner != Owner.PENDING || candidateDirection == null) return false
        owner = Owner.GROUP
        return true
    }

    fun onUp(timeMs: Long): Settlement? {
        val direction = candidateDirection
        val settlement = if (owner == Owner.GROUP && direction != null) {
            // A finger held still before release must not retain the velocity of an old flick.
            val velocity = if (timeMs - lastMotionTimeMs <= VELOCITY_TIMEOUT_MS) {
                velocityPxPerMs * direction.dragSign
            } else {
                0f
            }
            val projectedProgress = progress + velocity * VELOCITY_PROJECTION_MS / widthPx
            Settlement(
                direction = direction,
                commit = progress > 0f &&
                    (progress >= COMMIT_PROGRESS || projectedProgress >= COMMIT_PROGRESS),
            )
        } else {
            null
        }
        reset()
        return settlement
    }

    fun onCancel(): Settlement? {
        val settlement = candidateDirection?.takeIf { owner == Owner.GROUP }
            ?.let { Settlement(it, commit = false) }
        reset()
        return settlement
    }

    private fun reset() {
        owner = Owner.IDLE
        candidateDirection = null
        progress = 0f
        velocityPxPerMs = 0f
    }

    companion object {
        private const val HORIZONTAL_DOMINANCE = 1.15f
        private const val COMMIT_PROGRESS = 1f / 3f
        private const val VELOCITY_PROJECTION_MS = 160f
        private const val VELOCITY_TIMEOUT_MS = 100L
    }
}
