package dev.ujhhgtg.wekit.pet.core

/**
 * Pet state machine — pure, clock-injected. Ported 1:1 from dsh-pet state.ts.
 * Maps activity phases onto the 9-state animation contract, plus a one-shot
 * "celebration" window after `done` and a "failure" window after `failed` so
 * the pet visibly reacts before settling back to idle.
 */

/** One input snapshot consumed by the machine. */
data class PetStateInput(
    val phase: ActivityPhase,
    val line: String? = null,
    val phrase: String? = null,
)

/** Animation decision plus the copy the pet should show. */
data class PetStateSnapshot(
    val animation: PetAnimation,
    val bubble: String?,
    val animationStartedAt: Long,
    val phase: ActivityPhase,
    val sessionActive: Boolean,
)

/** Machine configuration. */
data class PetStateConfig(
    val celebrateMs: Long = 2400,
    val failureMs: Long = 2400,
)

/**
 * PetStateMachine — one instance per active session. Holds only the latest
 * input snapshot and terminal-state timing; no storage, no side effects.
 */
class PetStateMachine(
    private val config: PetStateConfig = PetStateConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var phase: ActivityPhase = ActivityPhase.IDLE
    private var line: String? = null
    private var phrase: String? = null
    private var sessionActive = false
    private var doneAt: Long? = null
    private var failedAt: Long? = null

    /** Consume one projected activity update. */
    fun onActivityStatus(input: PetStateInput) {
        phase = input.phase
        line = input.line
        phrase = input.phrase
        doneAt = if (input.phase == ActivityPhase.DONE) now() else null
        failedAt = if (input.phase == ActivityPhase.FAILED) now() else null
    }

    /** A session became the active one (or a fresh session started). */
    fun onSessionActive() {
        sessionActive = true
    }

    /** The active session was disposed (or none left). */
    fun onSessionDisposed() {
        sessionActive = false
        phase = ActivityPhase.IDLE
        line = null
        phrase = null
        doneAt = null
        failedAt = null
    }

    /** Render the current animation decision. */
    fun render(): PetStateSnapshot {
        val nowMs = now()
        var animation = animationForPhase(phase)
        val doneSettled = phase == ActivityPhase.DONE &&
            doneAt != null && nowMs - doneAt!! >= config.celebrateMs
        val failedSettled = phase == ActivityPhase.FAILED &&
            failedAt != null && nowMs - failedAt!! >= config.failureMs
        if (doneSettled || failedSettled) animation = PetAnimation.IDLE
        val settled = phase == ActivityPhase.IDLE || doneSettled || failedSettled
        val bubble = if (settled) null else (phrase ?: line)
        // Expose the *effective* phase so sequence-driven rendering settles back to idle once the
        // one-shot done/failed window elapses (the animation field mirrors it).
        val effectivePhase = if (doneSettled || failedSettled) ActivityPhase.IDLE else phase
        return PetStateSnapshot(
            animation = animation,
            bubble = bubble,
            animationStartedAt = nowMs,
            phase = effectivePhase,
            sessionActive = sessionActive,
        )
    }
}
