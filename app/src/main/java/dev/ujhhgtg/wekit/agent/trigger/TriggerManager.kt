package dev.ujhhgtg.wekit.agent.trigger

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Owns the trigger runtime: wires the [TriggerScheduler] (SCHEDULE) and [EventTriggerBus]
 * (MESSAGE / SQL) to a common dispatch path that resolves the target session, builds the injected
 * text (event timeline + the trigger's prompt template), and asks the host to run a turn.
 *
 * The host (WeAgentService) provides the coupling to the turn engine + session creation via
 * [TriggerHost], keeping the trigger package free of any direct dependency on the service/UI layer.
 *
 * Lifecycle: [start] once (from WeAgentService.init) after the DB is warm. It observes the trigger
 * table and re-syncs the scheduler + bus on any change, so create/update/delete/enable all take
 * effect live. SESSION-scoped triggers whose session was deleted are already gone (cascade in
 * WeAgentRepository.deleteSession), so no extra cleanup is needed here.
 */
class TriggerManager(
    private val scope: CoroutineScope,
    private val host: TriggerHost,
) {
    /** The host's coupling to the turn engine / session store. Implemented by WeAgentService. */
    interface TriggerHost {
        /** Runs a triggered turn on [sessionId] with the already-built [injectedText] (timeline + prompt). */
        suspend fun runTriggeredTurn(sessionId: String, injectedText: String)

        /**
         * Creates a fresh background session for a GLOBAL trigger (applying new-session defaults),
         * WITHOUT switching the foreground to it. Returns the new session id, or null if no model is
         * configured (in which case the fire is skipped).
         */
        suspend fun createBackgroundSession(): String?
    }

    private companion object {
        const val TAG = "TriggerManager"

        /**
         * Rate limit for GLOBAL triggers creating new sessions: at most this many new sessions across
         * ALL global triggers within [GLOBAL_WINDOW_MS], so a misconfigured event trigger can't spawn
         * hundreds of sessions in a burst.
         */
        const val GLOBAL_MAX_SESSIONS_PER_WINDOW = 10
        const val GLOBAL_WINDOW_MS = 60_000L
    }

    private val scheduler = TriggerScheduler(
        scope = scope,
        onFire = { trigger -> dispatch(trigger, listOf(TriggeredEvent.Schedule(Instant.now()))) },
        onDisable = { id -> WeAgentRepository.setTriggerEnabled(id, false) },
    )

    private val bus = EventTriggerBus(
        scope = scope,
        onFlush = { trigger, events -> dispatch(trigger, events) },
    )

    // Sliding window of recent GLOBAL session-creation timestamps (epoch millis).
    private val globalSessionStamps = ArrayDeque<Long>()
    private val lastGlobalStampLock = Any()

    fun start() {
        // The bus registers/unregisters its DB listeners itself inside resync() based on whether any
        // event trigger is eligible, so no explicit attach() is needed here.
        scope.launch {
            WeAgentRepository.observeTriggers().collectLatest { triggers ->
                runCatching {
                    scheduler.resync(triggers)
                    bus.resync(triggers)
                }.onFailure { WeLogger.e(TAG, "trigger resync failed", it) }
            }
        }
        WeLogger.i(TAG, "trigger manager started")
    }

    /** Briefly suppress SQL-event triggers around the agent's own DB tool calls (anti-loop). */
    fun suppressSqlBriefly(windowMillis: Long = 1_500) = bus.suppressSqlBriefly(windowMillis)

    /**
     * The single fire path for both schedule and event triggers. Enforces cooldown, resolves the
     * target session (creating one for GLOBAL), builds the injected text, records lastFiredAt, and
     * asks the host to run the turn.
     */
    private suspend fun dispatch(trigger: TriggerEntity, events: List<TriggeredEvent>) {
        // Reload the freshest row (enabled state / cooldown may have changed since it was scheduled).
        val current = WeAgentRepository.getTrigger(trigger.id) ?: return
        if (!current.enabled) return

        // Cooldown: skip if we fired too recently.
        if (current.cooldownMillis > 0) {
            val last = current.lastFiredAt?.toEpochMilli()
            if (last != null && System.currentTimeMillis() - last < current.cooldownMillis) {
                WeLogger.i(TAG, "trigger ${current.id} in cooldown; skipping")
                return
            }
        }

        val sessionId = resolveSession(current) ?: return
        val injected = TriggerTimeline.render(current.name, events) + "\n\n" + current.promptTemplate

        // Record the fire time first, so cooldown holds even if the turn is long-running.
        WeAgentRepository.setTriggerLastFiredAt(current.id, Instant.now())

        WeLogger.i(TAG, "trigger ${current.id} (${current.name}) firing on session $sessionId with ${events.size} event(s)")
        host.runTriggeredTurn(sessionId, injected)
    }

    /**
     * Resolves the session a trigger fires into. SESSION scope uses its bound [TriggerEntity.sessionId]
     * (disabling the trigger if that session vanished — normally the cascade delete already removed it).
     * GLOBAL scope always creates a fresh background session, subject to the rate limit.
     */
    private suspend fun resolveSession(trigger: TriggerEntity): String? = when (trigger.scope) {
        TriggerScope.SESSION -> {
            val sid = trigger.sessionId
            if (sid == null || WeAgentRepository.getSession(sid) == null) {
                WeLogger.w(TAG, "SESSION trigger ${trigger.id} has no live session; disabling")
                WeAgentRepository.setTriggerEnabled(trigger.id, false)
                null
            } else sid
        }
        TriggerScope.GLOBAL -> {
            if (!allowGlobalSession()) {
                WeLogger.w(TAG, "GLOBAL trigger ${trigger.id} rate-limited; skipping fire")
                null
            } else {
                host.createBackgroundSession().also {
                    if (it == null) WeLogger.w(TAG, "GLOBAL trigger ${trigger.id}: no model configured; skipping")
                }
            }
        }
    }

    /** Sliding-window rate limiter for GLOBAL session creation. */
    private fun allowGlobalSession(): Boolean = synchronized(lastGlobalStampLock) {
        val now = System.currentTimeMillis()
        while (globalSessionStamps.isNotEmpty() && now - globalSessionStamps.first() > GLOBAL_WINDOW_MS) {
            globalSessionStamps.removeFirst()
        }
        if (globalSessionStamps.size >= GLOBAL_MAX_SESSIONS_PER_WINDOW) return false
        globalSessionStamps.addLast(now)
        true
    }
}
