package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.trigger.MessageDirection
import dev.ujhhgtg.wekit.agent.trigger.ScheduleKind
import dev.ujhhgtg.wekit.agent.trigger.SqlOp
import dev.ujhhgtg.wekit.agent.trigger.TriggerConditions
import dev.ujhhgtg.wekit.agent.trigger.TriggerConditionsJson
import dev.ujhhgtg.wekit.agent.trigger.TriggerScope
import dev.ujhhgtg.wekit.agent.trigger.TriggerType
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import dev.ujhhgtg.wekit.utils.WeLogger
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Built-in `builtin-trigger` tools that let the agent manage its OWN triggers (§ triggers, AI
 * self-configuration). The read tool is `sideEffect = false` (factory-default ENABLED); every
 * mutating tool is `sideEffect = true` (factory-default MANUAL_APPROVAL) so, per the user's choice,
 * the agent's trigger changes flow through the normal 4-state permission gate — the user can relax
 * them to auto/smart or lock them to manual/disabled per tool.
 *
 * Scope handling: SESSION-scoped triggers default to "this session" — the id is read from the
 * [AgentSessionContext] installed around the running turn. GLOBAL triggers always run in a fresh
 * session when they fire (they have no bound session).
 */
object WeTriggerToolBindings {

    private const val GROUP = AgentTool.BUILTIN_TRIGGER
    private const val TAG = "WeTriggerTools"

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @AgentTool(
        name = "trigger-list",
        description = "List WeAgent triggers. Pass scope='session' to list only the current session's triggers, 'global' for global ones, or 'all' (default) for everything. Returns each trigger's id, name, type, scope, enabled state, and a short config summary.",
        sideEffect = false,
        group = GROUP,
    )
    suspend fun triggerList(
        @AgentToolParam("Filter: all | session | global (default all)") scope: String?,
    ): String {
        val currentSession = coroutineContext[AgentSessionContext]?.sessionId
        val all = WeAgentRepository.getAllTriggersOnce()
        val filtered = when (scope?.lowercase()) {
            "session" -> all.filter { it.scope == TriggerScope.SESSION && it.sessionId == currentSession }
            "global" -> all.filter { it.scope == TriggerScope.GLOBAL }
            else -> all
        }
        if (filtered.isEmpty()) return "No triggers."
        return filtered.joinToString("\n") { t -> summarize(t) }
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @AgentTool(
        name = "trigger-create-schedule",
        description = "Create a scheduled trigger that fires a turn on a timer. scheduleKind is one of: interval (every intervalSeconds), daily (at dailyMinuteOfDay minutes past local midnight), cron (5-field cron in cronExpr, local time), once (single fire at atEpochMillis). By default (omit scope) it is bound to the CURRENT session and fires back into this same conversation; only pass scope='global' if the user explicitly wants a new/separate conversation each fire. promptTemplate is the instruction the agent runs when it fires.",
        sideEffect = true,
        group = GROUP,
    )
    suspend fun triggerCreateSchedule(
        @AgentToolParam("Human-readable trigger name") name: String,
        @AgentToolParam("Prompt the agent runs when the trigger fires") promptTemplate: String,
        @AgentToolParam("Schedule kind: interval | daily | cron | once") scheduleKind: String,
        @AgentToolParam("interval: seconds between fires") intervalSeconds: Long?,
        @AgentToolParam("daily: minutes past local midnight (0..1439)") dailyMinuteOfDay: Int?,
        @AgentToolParam("cron: 5-field cron expression (local time)") cronExpr: String?,
        @AgentToolParam("once: absolute fire time in epoch millis") atEpochMillis: Long?,
        @AgentToolParam("Scope: OMIT this (or pass 'session') to bind the trigger to the CURRENT session so it fires back into this same conversation — this is what you want almost always. Only pass 'global' if the user explicitly asked for a brand-new/separate conversation on every fire.") scope: String?,
    ): String {
        val kind = when (scheduleKind.lowercase()) {
            "interval" -> ScheduleKind.INTERVAL
            "daily" -> ScheduleKind.DAILY
            "cron" -> ScheduleKind.CRON
            "once" -> ScheduleKind.ONCE
            else -> return "Error: unknown scheduleKind '$scheduleKind' (use interval|daily|cron|once)"
        }
        // Validate the kind-specific field is present & sane.
        when (kind) {
            ScheduleKind.INTERVAL -> if ((intervalSeconds ?: 0) <= 0) return "Error: interval requires intervalSeconds > 0"
            ScheduleKind.DAILY -> if ((dailyMinuteOfDay ?: -1) !in 0..1439) return "Error: daily requires dailyMinuteOfDay in 0..1439"
            ScheduleKind.CRON -> if (cronExpr.isNullOrBlank()) return "Error: cron requires cronExpr"
            ScheduleKind.ONCE -> if ((atEpochMillis ?: 0) <= System.currentTimeMillis()) return "Error: once requires atEpochMillis in the future"
        }
        val resolvedScope = resolveScope(scope) ?: return SCOPE_ERROR
        val boundSession = if (resolvedScope == TriggerScope.SESSION) {
            coroutineContext[AgentSessionContext]?.sessionId
                ?: return "Error: cannot resolve current session for a session-scoped trigger"
        } else null

        val trigger = baseTrigger(name, promptTemplate, TriggerType.SCHEDULE, resolvedScope, boundSession).copy(
            scheduleKind = kind,
            intervalSeconds = intervalSeconds.takeIf { kind == ScheduleKind.INTERVAL },
            dailyMinuteOfDay = dailyMinuteOfDay.takeIf { kind == ScheduleKind.DAILY },
            cronExpr = cronExpr.takeIf { kind == ScheduleKind.CRON },
            atEpochMillis = atEpochMillis.takeIf { kind == ScheduleKind.ONCE },
        )
        WeAgentRepository.upsertTrigger(trigger)
        WeLogger.i(TAG, "AI created schedule trigger id=${trigger.id} scope=${trigger.scope} boundSession=${trigger.sessionId}")
        val scopeNote = if (resolvedScope == TriggerScope.SESSION) " (bound to this session)" else " (GLOBAL: fires in a new session each time)"
        return "Created schedule trigger '${trigger.name}' (id=${trigger.id})$scopeNote."
    }

    @AgentTool(
        name = "trigger-create-message",
        description = "Create a trigger that fires when new WeChat messages arrive (buffered). Optional conditions: contentRegex (partial match on message text), talkerRegex (partial match on conversation id), msgTypes (comma-separated message type codes; empty = any), direction (received|sent|both, default received). Buffer: it waits bufferDebounceSeconds of silence before firing, flushing early at bufferMaxEvents messages or after bufferMaxWaitSeconds. cooldownSeconds sets a minimum gap between fires. By default (omit scope) the trigger is bound to the CURRENT session and fires back into this same conversation; only pass scope='global' if the user explicitly wants a new/separate conversation each fire.",
        sideEffect = true,
        group = GROUP,
    )
    suspend fun triggerCreateMessage(
        @AgentToolParam("Human-readable trigger name") name: String,
        @AgentToolParam("Prompt the agent runs when the trigger fires") promptTemplate: String,
        @AgentToolParam("Partial regex over message content (optional)") contentRegex: String?,
        @AgentToolParam("Partial regex over the conversation id / talker (optional)") talkerRegex: String?,
        @AgentToolParam("Comma-separated message type codes to match; empty = any") msgTypes: String?,
        @AgentToolParam("Direction: received (default) | sent | both") direction: String?,
        @AgentToolParam("Debounce seconds of silence before firing (default 3)") bufferDebounceSeconds: Int?,
        @AgentToolParam("Flush early after this many buffered messages (default 20)") bufferMaxEvents: Int?,
        @AgentToolParam("Hard cap seconds from first buffered message (default 30)") bufferMaxWaitSeconds: Int?,
        @AgentToolParam("Minimum seconds between fires (default 0)") cooldownSeconds: Int?,
        @AgentToolParam("Scope: OMIT this (or pass 'session') to bind the trigger to the CURRENT session so it fires back into this same conversation — this is what you want almost always. Only pass 'global' if the user explicitly asked for a brand-new/separate conversation on every fire.") scope: String?,
    ): String {
        val dir = when (direction?.lowercase()) {
            null, "", "received" -> MessageDirection.RECEIVED
            "sent" -> MessageDirection.SENT
            "both" -> MessageDirection.BOTH
            else -> return "Error: unknown direction '$direction' (use received|sent|both)"
        }
        val types = msgTypes?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
        val conditions = TriggerConditions(
            contentRegex = contentRegex?.takeIf { it.isNotBlank() },
            talkerRegex = talkerRegex?.takeIf { it.isNotBlank() },
            msgTypes = types,
            direction = dir,
        )
        return createEventTrigger(
            name, promptTemplate, TriggerType.MESSAGE, conditions, scope,
            bufferDebounceSeconds, bufferMaxEvents, bufferMaxWaitSeconds, cooldownSeconds,
        )
    }

    @AgentTool(
        name = "trigger-create-sql",
        description = "Create a trigger that fires when WeChat performs database operations (buffered). ops is a comma-separated subset of insert,update,query (empty = all). Optional conditions: tableRegex (partial match on affected table, insert/update), sqlRegex (partial match on raw SQL, query), valuesRegex (partial match on written values, insert/update). Buffer + cooldown same as message triggers. By default (omit scope) the trigger is bound to the CURRENT session and fires back into this same conversation; only pass scope='global' if the user explicitly wants a new/separate conversation each fire. NOTE: the agent's own SQL tool calls are automatically suppressed so they don't self-trigger.",
        sideEffect = true,
        group = GROUP,
    )
    suspend fun triggerCreateSql(
        @AgentToolParam("Human-readable trigger name") name: String,
        @AgentToolParam("Prompt the agent runs when the trigger fires") promptTemplate: String,
        @AgentToolParam("Comma-separated ops to match: insert,update,query (empty = all)") ops: String?,
        @AgentToolParam("Partial regex over affected table name (optional)") tableRegex: String?,
        @AgentToolParam("Partial regex over raw SQL text (optional)") sqlRegex: String?,
        @AgentToolParam("Partial regex over written values (optional)") valuesRegex: String?,
        @AgentToolParam("Debounce seconds of silence before firing (default 3)") bufferDebounceSeconds: Int?,
        @AgentToolParam("Flush early after this many buffered events (default 20)") bufferMaxEvents: Int?,
        @AgentToolParam("Hard cap seconds from first buffered event (default 30)") bufferMaxWaitSeconds: Int?,
        @AgentToolParam("Minimum seconds between fires (default 0)") cooldownSeconds: Int?,
        @AgentToolParam("Scope: OMIT this (or pass 'session') to bind the trigger to the CURRENT session so it fires back into this same conversation — this is what you want almost always. Only pass 'global' if the user explicitly asked for a brand-new/separate conversation on every fire.") scope: String?,
    ): String {
        val opList = ops?.split(',')?.mapNotNull {
            when (it.trim().lowercase()) {
                "insert" -> SqlOp.INSERT
                "update" -> SqlOp.UPDATE
                "query" -> SqlOp.QUERY
                else -> null
            }
        }.orEmpty()
        val conditions = TriggerConditions(
            sqlOps = opList,
            tableRegex = tableRegex?.takeIf { it.isNotBlank() },
            sqlRegex = sqlRegex?.takeIf { it.isNotBlank() },
            valuesRegex = valuesRegex?.takeIf { it.isNotBlank() },
        )
        return createEventTrigger(
            name, promptTemplate, TriggerType.SQL, conditions, scope,
            bufferDebounceSeconds, bufferMaxEvents, bufferMaxWaitSeconds, cooldownSeconds,
        )
    }

    // -------------------------------------------------------------------------
    // Mutate / delete
    // -------------------------------------------------------------------------

    @AgentTool(
        name = "trigger-set-enabled",
        description = "Enable or disable a trigger by id.",
        sideEffect = true,
        group = GROUP,
    )
    suspend fun triggerSetEnabled(
        @AgentToolParam("Trigger id") id: String,
        @AgentToolParam("true to enable, false to disable") enabled: Boolean,
    ): String {
        WeAgentRepository.getTrigger(id) ?: return "Error: no trigger with id=$id"
        WeAgentRepository.setTriggerEnabled(id, enabled)
        return if (enabled) "Enabled trigger $id." else "Disabled trigger $id."
    }

    @AgentTool(
        name = "trigger-delete",
        description = "Delete a trigger by id. Irreversible.",
        sideEffect = true,
        group = GROUP,
    )
    suspend fun triggerDelete(
        @AgentToolParam("Trigger id") id: String,
    ): String {
        WeAgentRepository.getTrigger(id) ?: return "Error: no trigger with id=$id"
        WeAgentRepository.deleteTrigger(id)
        return "Deleted trigger $id."
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private const val SCOPE_ERROR = "Error: unknown scope (use session|global)"

    private fun resolveScope(scope: String?): TriggerScope? = when (scope?.lowercase()) {
        null, "", "session" -> TriggerScope.SESSION
        "global" -> TriggerScope.GLOBAL
        else -> null
    }

    private fun baseTrigger(
        name: String,
        promptTemplate: String,
        type: TriggerType,
        scope: TriggerScope,
        sessionId: String?,
    ) = TriggerEntity(
        id = UUID.randomUUID().toString(),
        name = name,
        type = type,
        scope = scope,
        sessionId = sessionId,
        enabled = true,
        promptTemplate = promptTemplate,
        createdAt = Instant.now(),
    )

    private suspend fun createEventTrigger(
        name: String,
        promptTemplate: String,
        type: TriggerType,
        conditions: TriggerConditions,
        scope: String?,
        bufferDebounceSeconds: Int?,
        bufferMaxEvents: Int?,
        bufferMaxWaitSeconds: Int?,
        cooldownSeconds: Int?,
    ): String {
        val resolvedScope = resolveScope(scope) ?: return SCOPE_ERROR
        val boundSession = if (resolvedScope == TriggerScope.SESSION) {
            coroutineContext[AgentSessionContext]?.sessionId
                ?: return "Error: cannot resolve current session for a session-scoped trigger"
        } else null

        val trigger = baseTrigger(name, promptTemplate, type, resolvedScope, boundSession).copy(
            conditionsJson = TriggerConditionsJson.encode(conditions),
            bufferDebounceMillis = (bufferDebounceSeconds?.coerceAtLeast(0)?.toLong() ?: 3L) * 1000,
            bufferMaxEvents = bufferMaxEvents?.coerceAtLeast(1) ?: 20,
            bufferMaxWaitMillis = (bufferMaxWaitSeconds?.coerceAtLeast(1)?.toLong() ?: 30L) * 1000,
            cooldownMillis = (cooldownSeconds?.coerceAtLeast(0)?.toLong() ?: 0L) * 1000,
        )
        WeAgentRepository.upsertTrigger(trigger)
        WeLogger.i(TAG, "AI created ${type.name.lowercase()} trigger id=${trigger.id} scope=${trigger.scope} boundSession=${trigger.sessionId}")
        val scopeNote = if (resolvedScope == TriggerScope.SESSION) " (bound to this session)" else " (GLOBAL: fires in a new session each time)"
        return "Created ${type.name.lowercase()} trigger '${trigger.name}' (id=${trigger.id})$scopeNote."
    }

    private fun summarize(t: TriggerEntity): String = buildString {
        append("id=${t.id} name='${t.name}' type=${t.type} scope=${t.scope}")
        if (!t.enabled) append(" [disabled]")
        when (t.type) {
            TriggerType.SCHEDULE -> {
                append(" kind=${t.scheduleKind}")
                t.intervalSeconds?.let { append(" every ${it}s") }
                t.dailyMinuteOfDay?.let { append(" at ${it / 60}:${(it % 60).toString().padStart(2, '0')}") }
                t.cronExpr?.let { append(" cron='$it'") }
                t.atEpochMillis?.let { append(" at $it") }
            }
            TriggerType.MESSAGE, TriggerType.SQL -> {
                t.conditionsJson?.let { append(" conditions=$it") }
                append(" buffer(debounce=${t.bufferDebounceMillis}ms,max=${t.bufferMaxEvents},wait=${t.bufferMaxWaitMillis}ms)")
                if (t.cooldownMillis > 0) append(" cooldown=${t.cooldownMillis}ms")
            }
        }
    }
}
