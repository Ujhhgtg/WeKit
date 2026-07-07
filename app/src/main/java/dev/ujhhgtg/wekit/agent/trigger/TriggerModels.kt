package dev.ujhhgtg.wekit.agent.trigger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WeAgent trigger domain model. A trigger causes the agent to run a turn automatically — either on a
 * schedule, when a new WeChat message arrives, or when a WeChat SQL operation is observed.
 *
 * Triggers have two scopes: [TriggerScope.SESSION] triggers are bound to one chat session and run in
 * it (deleting the session cascades away its triggers); [TriggerScope.GLOBAL] triggers always create
 * a fresh session when they fire.
 *
 * These enums are stored by Room (by name, via [dev.ujhhgtg.wekit.agent.data.WeAgentConverters]) and
 * also drive the trigger engine, so they live in the trigger package rather than the entity file.
 */
enum class TriggerType { SCHEDULE, MESSAGE, SQL }

enum class TriggerScope { SESSION, GLOBAL }

/**
 * How a [TriggerType.SCHEDULE] trigger computes its next fire time.
 *  - [INTERVAL]: every `intervalSeconds` from the last fire (or from arm time on first run).
 *  - [DAILY]: once a day at `dailyMinuteOfDay` (minutes past local midnight, 0..1439).
 *  - [CRON]: a standard 5-field cron expression in `cronExpr` (local time).
 *  - [ONCE]: a single fire at `atEpochMillis`, then the trigger disables itself.
 */
enum class ScheduleKind { INTERVAL, DAILY, CRON, ONCE }

/** Which direction of message a [TriggerType.MESSAGE] trigger reacts to. */
@Serializable
enum class MessageDirection { RECEIVED, SENT, BOTH }

/** Which WeChat DB operation a [TriggerType.SQL] trigger reacts to. */
@Serializable
enum class SqlOp { INSERT, UPDATE, QUERY }

/**
 * Filter conditions for event triggers (MESSAGE / SQL), serialized to the trigger row's
 * `conditionsJson`. All fields are optional; an unset field imposes no constraint. Regexes are
 * matched with [Regex.containsMatchIn] (partial match) so users don't have to anchor them.
 *
 * SCHEDULE triggers have no conditions (their row stores `conditionsJson = null`).
 */
@Serializable
data class TriggerConditions(
    // --- MESSAGE conditions ---
    /** Partial regex over the message `content`. */
    val contentRegex: String? = null,
    /** Allowed message `type` codes (see [dev.ujhhgtg.wekit.features.api.core.models.MessageType]); null/empty = any. */
    val msgTypes: List<Int>? = null,
    /** Partial regex over the message `talker` (conversation id). */
    val talkerRegex: String? = null,
    /** Which direction of message to react to (default: only received). */
    val direction: MessageDirection = MessageDirection.RECEIVED,

    // --- SQL conditions ---
    /** Which SQL operations to react to; empty = all three (INSERT/UPDATE/QUERY). */
    val sqlOps: List<SqlOp> = emptyList(),
    /** Partial regex over the affected table name (INSERT/UPDATE only). */
    val tableRegex: String? = null,
    /** Partial regex over the raw SQL text (QUERY only). */
    val sqlRegex: String? = null,
    /** Partial regex over the stringified ContentValues (INSERT/UPDATE only). */
    val valuesRegex: String? = null,
)

/** JSON (de)serialization for [TriggerConditions], tolerant of unknown/missing fields. */
object TriggerConditionsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(conditions: TriggerConditions): String = json.encodeToString(conditions)

    /** Decodes stored conditions; returns an empty (no-constraint) instance on null or parse error. */
    fun decode(raw: String?): TriggerConditions =
        raw?.let { runCatching { json.decodeFromString<TriggerConditions>(it) }.getOrNull() }
            ?: TriggerConditions()
}
