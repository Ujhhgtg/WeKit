package dev.ujhhgtg.wekit.agent.trigger

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A single observed event that matched a trigger's conditions and is waiting in its buffer. The
 * dispatcher serializes an ordered list of these into the "event timeline" text that precedes the
 * user's prompt template when the trigger fires.
 */
sealed interface TriggeredEvent {
    val at: Instant

    /** A [TriggerType.SCHEDULE] fire — no external data, just the fire time. */
    data class Schedule(override val at: Instant) : TriggeredEvent

    /** A new WeChat message row (from the `message` table insert). */
    data class Message(
        override val at: Instant,
        val talker: String?,
        val type: Int?,
        val isSend: Boolean,
        val content: String?,
    ) : TriggeredEvent

    /** A WeChat SQL operation (insert/update/query). */
    data class Sql(
        override val at: Instant,
        val op: SqlOp,
        val table: String?,
        val sql: String?,
        val values: String?,
    ) : TriggeredEvent
}

/**
 * Renders buffered events into a compact, model-readable timeline. The user's prompt template is
 * appended by the dispatcher after this block.
 */
object TriggerTimeline {

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun ts(at: Instant): String = timeFmt.format(at)

    /**
     * Builds the "[系统提醒] ..." context header + one line per event. Always prefixed with a system
     * reminder that this turn was fired automatically (not by a human), so the model treats it as
     * background context rather than a live user utterance.
     */
    fun render(triggerName: String, events: List<TriggeredEvent>): String = buildString {
        append("[系统提醒] 本轮由触发器「")
        append(triggerName)
        append("」自动触发（非用户手动发送）。")
        if (events.isEmpty()) {
            append("\n触发时间：").append(ts(Instant.now()))
            return@buildString
        }
        append("\n以下是触发期间累积的 ")
        append(events.size)
        append(" 个事件（按时间排序）：")
        events.forEachIndexed { i, ev ->
            append("\n").append(i + 1).append(". ").append(renderEvent(ev))
        }
    }

    private fun renderEvent(ev: TriggeredEvent): String = when (ev) {
        is TriggeredEvent.Schedule -> "[${ts(ev.at)}] 定时触发"
        is TriggeredEvent.Message -> buildString {
            append("[${ts(ev.at)}] 消息")
            ev.type?.let { append(" type=$it") }
            append(if (ev.isSend) " 方向=发出" else " 方向=收到")
            ev.talker?.let { append(" 会话=$it") }
            ev.content?.let { append(" 内容=").append(truncate(it)) }
        }
        is TriggeredEvent.Sql -> buildString {
            append("[${ts(ev.at)}] SQL ").append(ev.op.name)
            ev.table?.let { append(" 表=$it") }
            ev.sql?.let { append(" 语句=").append(truncate(it)) }
            ev.values?.let { append(" 值=").append(truncate(it)) }
        }
    }

    private fun truncate(s: String, max: Int = 500): String =
        if (s.length <= max) s else s.take(max) + "…(${s.length - max} more)"
}
