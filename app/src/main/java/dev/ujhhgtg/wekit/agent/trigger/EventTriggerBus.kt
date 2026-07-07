package dev.ujhhgtg.wekit.agent.trigger

import android.content.ContentValues
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges WeChat DB events ([WeDatabaseListenerApi]) into WeAgent [TriggerType.MESSAGE] /
 * [TriggerType.SQL] triggers. For each enabled event trigger it owns a [TriggerBuffer]; incoming
 * events are matched against the trigger's [TriggerConditions] and, if they pass (plus the anti-loop
 * filters), offered to that buffer. When a buffer flushes it calls back into [onFlush] with the
 * trigger + the batch of events.
 *
 * Anti-loop (§ anti-loop, per the user's "default filter + cooldown" choice):
 *  - MESSAGE `filterOwnEvents` drops rows with `isSend == 1` — this covers both the user's own sends
 *    and anything the agent sent via tools (both go out under the logged-in identity).
 *  - SQL events are dropped while a [suppressSqlBriefly] window is open, so the agent's own
 *    `builtin-wechat-sql` writes don't re-trigger SQL triggers. The window is time-based because DB
 *    callbacks arrive on WeChat's own threads, not the agent's coroutine.
 */
class EventTriggerBus(
    private val scope: CoroutineScope,
    private val onFlush: suspend (TriggerEntity, List<TriggeredEvent>) -> Unit,
) : WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    WeDatabaseListenerApi.IQueryListener {

    private companion object {
        const val TAG = "EventTriggerBus"
    }

    // triggerId -> (trigger row, its buffer). Rebuilt on resync.
    private class Bound(val trigger: TriggerEntity, val conditions: TriggerConditions, val buffer: TriggerBuffer)

    private val message = ConcurrentHashMap<String, Bound>()
    private val sql = ConcurrentHashMap<String, Bound>()

    /** Epoch millis until which SQL events are suppressed (agent's own writes). 0 = not suppressed. */
    private val sqlSuppressedUntil = AtomicLong(0)

    @Volatile private var registered = false

    /** Opens/extends a suppression window so the agent's own SQL writes don't fire SQL triggers. */
    fun suppressSqlBriefly(windowMillis: Long) {
        val until = System.currentTimeMillis() + windowMillis
        sqlSuppressedUntil.updateAndGet { prev -> maxOf(prev, until) }
    }

    private val sqlSuppressed: Boolean
        get() = System.currentTimeMillis() < sqlSuppressedUntil.get()

    /**
     * Reconciles bound buffers against the current [triggers]. Cancels buffers for triggers that
     * disappeared / disabled, and (re)creates buffers for eligible MESSAGE / SQL triggers. Registers
     * the DB listeners on first eligible trigger; unregisters when none remain.
     */
    @Synchronized
    fun resync(triggers: List<TriggerEntity>) {
        rebuild(message, triggers.filter { it.type == TriggerType.MESSAGE && it.enabled })
        rebuild(sql, triggers.filter { it.type == TriggerType.SQL && it.enabled })

        val anyEligible = message.isNotEmpty() || sql.isNotEmpty()
        if (anyEligible && !registered) {
            WeDatabaseListenerApi.addListener(this)
            registered = true
            WeLogger.i(TAG, "registered DB listeners (message=${message.size}, sql=${sql.size})")
        } else if (!anyEligible && registered) {
            WeDatabaseListenerApi.removeListener(this)
            registered = false
            WeLogger.i(TAG, "unregistered DB listeners (no event triggers)")
        }
    }

    private fun rebuild(map: ConcurrentHashMap<String, Bound>, eligible: List<TriggerEntity>) {
        val wanted = eligible.associateBy { it.id }
        // Cancel & drop buffers no longer wanted.
        map.keys.filter { it !in wanted }.forEach { map.remove(it)?.buffer?.cancel() }
        // (Re)create every wanted buffer so edited conditions/buffer params take effect.
        for ((id, trigger) in wanted) {
            map.remove(id)?.buffer?.cancel()
            val conditions = TriggerConditionsJson.decode(trigger.conditionsJson)
            val buffer = TriggerBuffer(
                triggerId = trigger.id,
                scope = scope,
                debounceMillis = trigger.bufferDebounceMillis,
                maxEvents = trigger.bufferMaxEvents,
                maxWaitMillis = trigger.bufferMaxWaitMillis,
                onFlush = { batch -> onFlush(trigger, batch) },
            )
            map[id] = Bound(trigger, conditions, buffer)
        }
    }

    fun cancelAll() {
        if (registered) {
            WeDatabaseListenerApi.removeListener(this)
            registered = false
        }
        message.values.forEach { it.buffer.cancel() }
        sql.values.forEach { it.buffer.cancel() }
        message.clear()
        sql.clear()
    }

    // ---------------------------------------------------------------------------
    // DB listener callbacks (run on WeChat's threads)
    // ---------------------------------------------------------------------------

    override fun onInsert(table: String, values: ContentValues) {
        // MESSAGE triggers.
        if (table == "message" && message.isNotEmpty()) {
            val isSend = (values.getAsInteger("isSend") ?: 0) == 1
            val type = values.getAsInteger("type")
            val talker = values.getAsString("talker")
            val content = values.getAsString("content")
            val event = TriggeredEvent.Message(Instant.now(), talker, type, isSend, content)
            for (bound in message.values) {
                if (matchesMessage(bound, event)) bound.buffer.offer(event)
            }
        }
        // SQL INSERT triggers.
        dispatchSql(SqlOp.INSERT, table = table, sql = null, values = values.toString())
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        dispatchSql(SqlOp.UPDATE, table = table, sql = whereClause, values = values.toString())
    }

    override fun onQuery(sql: String): String? {
        dispatchSql(SqlOp.QUERY, table = null, sql = sql, values = null)
        return null // never rewrite the query
    }

    private fun dispatchSql(op: SqlOp, table: String?, sql: String?, values: String?) {
        if (this.sql.isEmpty()) return
        if (sqlSuppressed) return // agent's own writes
        val event = TriggeredEvent.Sql(Instant.now(), op, table, sql, values)
        for (bound in this.sql.values) {
            if (matchesSql(bound, event)) bound.buffer.offer(event)
        }
    }

    // ---------------------------------------------------------------------------
    // Condition matching
    // ---------------------------------------------------------------------------

    private fun matchesMessage(bound: Bound, ev: TriggeredEvent.Message): Boolean {
        val c = bound.conditions
        // Anti-loop: skip own sends (also covers agent tool sends).
        if (bound.trigger.filterOwnEvents && ev.isSend) return false
        // Direction.
        when (c.direction) {
            MessageDirection.RECEIVED -> if (ev.isSend) return false
            MessageDirection.SENT -> if (!ev.isSend) return false
            MessageDirection.BOTH -> Unit
        }
        // Message type.
        c.msgTypes?.takeIf { it.isNotEmpty() }?.let { types ->
            if (ev.type == null || ev.type !in types) return false
        }
        // Talker regex.
        if (!regexOk(c.talkerRegex, ev.talker)) return false
        // Content regex.
        if (!regexOk(c.contentRegex, ev.content)) return false
        return true
    }

    private fun matchesSql(bound: Bound, ev: TriggeredEvent.Sql): Boolean {
        val c = bound.conditions
        // Operation set (empty = all).
        if (c.sqlOps.isNotEmpty() && ev.op !in c.sqlOps) return false
        if (!regexOk(c.tableRegex, ev.table)) return false
        if (!regexOk(c.sqlRegex, ev.sql)) return false
        if (!regexOk(c.valuesRegex, ev.values)) return false
        return true
    }

    /** A null/blank pattern imposes no constraint; otherwise the value must contain a match. */
    private fun regexOk(pattern: String?, value: String?): Boolean {
        if (pattern.isNullOrBlank()) return true
        if (value == null) return false
        return runCatching { Regex(pattern).containsMatchIn(value) }.getOrDefault(false)
    }
}
