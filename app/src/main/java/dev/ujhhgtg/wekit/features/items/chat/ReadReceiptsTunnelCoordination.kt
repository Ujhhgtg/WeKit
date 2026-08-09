package dev.ujhhgtg.wekit.features.items.chat

/**
 * Serializes ownership of the process-global native handle by configuration generation.
 * Native operations execute while holding this monitor, so a stale cleanup cannot race a new start.
 */
internal class TunnelNativeLease {
    private var currentGeneration = 0L
    private var ownerGeneration: Long? = null

    @Synchronized
    fun advance(generation: Long): Boolean {
        if (generation < currentGeneration) return false
        if (ownerGeneration == currentGeneration) ownerGeneration = generation
        currentGeneration = generation
        return true
    }

    @Synchronized
    fun startIfCurrent(generation: Long, start: () -> Boolean): Boolean {
        if (currentGeneration != generation || ownerGeneration != null) return false
        if (!start()) return false
        ownerGeneration = generation
        return true
    }

    @Synchronized
    fun stopIfOwner(generation: Long, stop: () -> Unit): Boolean {
        if (currentGeneration != generation || ownerGeneration != generation) return false
        ownerGeneration = null
        stop()
        return true
    }

    /** Stops whichever older owner preceded [generation], but only while that generation is current. */
    @Synchronized
    fun stopForReplacement(generation: Long, stop: () -> Unit): Boolean {
        if (currentGeneration != generation) return false
        if (ownerGeneration != null) {
            ownerGeneration = null
            stop()
        }
        return true
    }

    @Synchronized
    fun ownerGeneration(): Long? = ownerGeneration
}

internal data class StopRegistration(
    val generation: Long,
    val shouldSend: Boolean,
)

internal data class StopDrain(
    val matched: Boolean,
    val callbacks: List<() -> Unit> = emptyList(),
)

/** Collects concurrent stop callers and lets exactly one terminal path drain their callbacks. */
internal class TunnelStopCompletion {
    private data class Pending(
        val generation: Long,
        val callbacks: MutableList<() -> Unit>,
    )

    private var pending: Pending? = null
    private var completedGeneration: Long? = null

    @Synchronized
    fun register(
        callback: (() -> Unit)?,
        generationFactory: () -> Long,
    ): StopRegistration {
        pending?.let { current ->
            if (callback != null && current.callbacks.isEmpty()) current.callbacks += callback
            return StopRegistration(current.generation, shouldSend = false)
        }
        val generation = generationFactory()
        pending = Pending(
            generation,
            mutableListOf<() -> Unit>().apply { if (callback != null) add(callback) },
        )
        return StopRegistration(generation, shouldSend = true)
    }

    @Synchronized
    fun complete(generation: Long): StopDrain {
        val current = pending ?: return StopDrain(matched = completedGeneration == generation)
        if (current.generation != generation) return StopDrain(matched = false)
        pending = null
        completedGeneration = generation
        return StopDrain(matched = true, callbacks = current.callbacks.toList())
    }

    @Synchronized
    fun pendingGeneration(): Long? = pending?.generation
}

/** Prevents late ACK/timeout events from completing or clearing a replacement START command. */
internal class TunnelHandoffGate {
    private var pendingGeneration: Long? = null

    @Synchronized
    fun begin(generation: Long): Long? = pendingGeneration.also {
        pendingGeneration = generation
    }

    @Synchronized
    fun complete(generation: Long): Boolean = clearIfCurrent(generation)

    @Synchronized
    fun fail(generation: Long): Boolean = clearIfCurrent(generation)

    @Synchronized
    fun pendingGeneration(): Long? = pendingGeneration

    private fun clearIfCurrent(generation: Long): Boolean {
        if (pendingGeneration != generation) return false
        pendingGeneration = null
        return true
    }
}
