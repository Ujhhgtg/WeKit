package dev.ujhhgtg.wekit.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.ujhhgtg.wekit.agent.trigger.ScheduleKind
import dev.ujhhgtg.wekit.agent.trigger.TriggerScope
import dev.ujhhgtg.wekit.agent.trigger.TriggerType
import java.time.Instant

/**
 * A WeAgent trigger (§ triggers). Persisted in the `triggers` table. A trigger fires a turn either on
 * a schedule ([TriggerType.SCHEDULE]) or in reaction to a WeChat DB event ([TriggerType.MESSAGE] /
 * [TriggerType.SQL]).
 *
 * SESSION-scoped triggers reference [sessionId] and are deleted when that session is deleted (see the
 * index + WeAgentRepository.deleteSession). GLOBAL-scoped triggers have `sessionId == null` and spin
 * up a fresh session each time they fire.
 *
 * Schedule fields are only meaningful for [TriggerType.SCHEDULE]; condition/buffer/anti-loop fields
 * only for the event types. Unused fields are left at their defaults.
 */
@Entity(
    tableName = "triggers",
    indices = [Index("sessionId")],
)
data class TriggerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: TriggerType,
    val scope: TriggerScope,
    /** Bound session for SESSION scope; null for GLOBAL. */
    val sessionId: String?,
    val enabled: Boolean,

    /**
     * User-authored prompt appended after the serialized event timeline when the trigger fires. This
     * is the instruction the agent acts on (the timeline gives it the "what just happened" context).
     */
    val promptTemplate: String,

    // --- SCHEDULE fields ---
    val scheduleKind: ScheduleKind? = null,
    /** [ScheduleKind.INTERVAL]: seconds between fires. */
    val intervalSeconds: Long? = null,
    /** [ScheduleKind.CRON]: 5-field cron expression (local time). */
    val cronExpr: String? = null,
    /** [ScheduleKind.DAILY]: minutes past local midnight (0..1439). */
    val dailyMinuteOfDay: Int? = null,
    /** [ScheduleKind.ONCE]: absolute fire time (epoch millis). */
    val atEpochMillis: Long? = null,

    // --- EVENT (MESSAGE / SQL) condition + buffer + anti-loop fields ---
    /** Serialized [dev.ujhhgtg.wekit.agent.trigger.TriggerConditions]; null for SCHEDULE. */
    val conditionsJson: String? = null,

    /**
     * Event buffering (§ event buffer). After the first matching event, the trigger waits
     * [bufferDebounceMillis] of silence before flushing; each new matching event resets that timer.
     * It flushes early if [bufferMaxEvents] accumulate, and unconditionally after [bufferMaxWaitMillis]
     * from the first buffered event. SCHEDULE triggers ignore these (they fire immediately).
     */
    val bufferDebounceMillis: Long = 3_000,
    val bufferMaxEvents: Int = 20,
    val bufferMaxWaitMillis: Long = 30_000,

    /**
     * Anti-loop (§ anti-loop). [filterOwnEvents]: skip messages sent as the logged-in user (isSend==1)
     * — this also covers messages the agent itself sent via tools, since those go out under the user's
     * identity. [cooldownMillis]: minimum spacing between two fires of this trigger.
     */
    val filterOwnEvents: Boolean = true,
    val cooldownMillis: Long = 0,

    /** Last time this trigger fired (epoch millis), for cooldown + INTERVAL scheduling; null = never. */
    val lastFiredAt: Instant? = null,
    val createdAt: Instant,
)
