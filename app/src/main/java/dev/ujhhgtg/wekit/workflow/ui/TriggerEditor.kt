package dev.ujhhgtg.wekit.workflow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import dev.ujhhgtg.wekit.workflow.model.ScheduleSpec
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger

// ─────────────────────────────────────────────────────────────────────────────
// Trigger type index helpers
// ─────────────────────────────────────────────────────────────────────────────

private val TRIGGER_LABELS = listOf(
    "手动触发",
    "收到消息",
    "新朋友圈",
    "收到转账",
    "收到红包",
    "定时触发",
)

private fun triggerToIndex(trigger: WorkflowTrigger?): Int = when (trigger) {
    null -> 0
    is WorkflowTrigger.NewMessage -> 1
    is WorkflowTrigger.NewMoment -> 2
    is WorkflowTrigger.NewTransfer -> 3
    is WorkflowTrigger.NewRedPacket -> 4
    is WorkflowTrigger.Schedule -> 5
}

private fun indexToDefaultTrigger(index: Int): WorkflowTrigger? = when (index) {
    0 -> null
    1 -> WorkflowTrigger.NewMessage()
    2 -> WorkflowTrigger.NewMoment()
    3 -> WorkflowTrigger.NewTransfer()
    4 -> WorkflowTrigger.NewRedPacket()
    5 -> WorkflowTrigger.Schedule(ScheduleSpec.Interval(60L))
    else -> null
}

// ─────────────────────────────────────────────────────────────────────────────
// TriggerSection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Top-level trigger picker + inline config fields. Intended to be placed inside
 * a Card or directly in a LazyListScope item in the workflow editor screen.
 */
@Composable
fun TriggerSection(
    trigger: WorkflowTrigger?,
    onTriggerChange: (WorkflowTrigger?) -> Unit,
) {
    val selectedIndex = triggerToIndex(trigger)

    WindowDropdownPreference(
        title = "触发方式",
        items = TRIGGER_LABELS,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { idx ->
            if (idx != selectedIndex) {
                onTriggerChange(indexToDefaultTrigger(idx))
            }
        },
    )

    when (trigger) {
        null -> Unit

        is WorkflowTrigger.NewMessage -> {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 12.dp)) {
                TextField(
                    value = trigger.talkerRegex.orEmpty(),
                    onValueChange = { v ->
                        onTriggerChange(trigger.copy(talkerRegex = v.ifBlank { null }))
                    },
                    label = "会话 ID 正则（留空 = 全部）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = trigger.contentRegex.orEmpty(),
                    onValueChange = { v ->
                        onTriggerChange(trigger.copy(contentRegex = v.ifBlank { null }))
                    },
                    label = "消息内容正则（留空 = 全部）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is WorkflowTrigger.NewMoment -> {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 12.dp)) {
                TextField(
                    value = trigger.senderWxids?.joinToString(",").orEmpty(),
                    onValueChange = { v ->
                        onTriggerChange(
                            trigger.copy(senderWxids = v.toWxidList())
                        )
                    },
                    label = "发送人 wxid（逗号分隔，留空 = 全部）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is WorkflowTrigger.NewTransfer -> {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 12.dp)) {
                TextField(
                    value = trigger.senderWxids?.joinToString(",").orEmpty(),
                    onValueChange = { v ->
                        onTriggerChange(
                            trigger.copy(senderWxids = v.toWxidList())
                        )
                    },
                    label = "发送人 wxid（逗号分隔，留空 = 全部）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is WorkflowTrigger.NewRedPacket -> {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 12.dp)) {
                TextField(
                    value = trigger.senderWxids?.joinToString(",").orEmpty(),
                    onValueChange = { v ->
                        onTriggerChange(
                            trigger.copy(senderWxids = v.toWxidList())
                        )
                    },
                    label = "发送人 wxid（逗号分隔，留空 = 全部）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is WorkflowTrigger.Schedule -> {
            Spacer(Modifier.height(8.dp))
            ScheduleKindPicker(
                spec = trigger.spec,
                onSpecChange = { onTriggerChange(trigger.copy(spec = it)) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ScheduleKindPicker
// ─────────────────────────────────────────────────────────────────────────────

private val SCHEDULE_KIND_LABELS = listOf(
    "间隔",
    "每天",
    "Cron 表达式",
    "单次执行",
)

private fun specToKindIndex(spec: ScheduleSpec?): Int = when (spec) {
    is ScheduleSpec.Interval -> 0
    is ScheduleSpec.Daily -> 1
    is ScheduleSpec.Cron -> 2
    is ScheduleSpec.Once -> 3
    null -> 0
}

/** Formats an ISO "HH:MM" string from a minuteOfDay value. */
private fun minuteOfDayToHhmm(minuteOfDay: Int): String {
    val h = (minuteOfDay / 60).coerceIn(0, 23)
    val m = (minuteOfDay % 60).coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

/** Parses "HH:MM" into minuteOfDay, returns null if the format is invalid. */
private fun hhmmToMinuteOfDay(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Composable
private fun ScheduleKindPicker(
    spec: ScheduleSpec?,
    onSpecChange: (ScheduleSpec) -> Unit,
) {
    val kindIndex = specToKindIndex(spec)

    Column(Modifier.padding(horizontal = 12.dp)) {
        WindowDropdownPreference(
            title = "定时方式",
            items = SCHEDULE_KIND_LABELS,
            selectedIndex = kindIndex,
            onSelectedIndexChange = { idx ->
                if (idx != kindIndex) {
                    val defaultSpec: ScheduleSpec = when (idx) {
                        0 -> ScheduleSpec.Interval(60L)
                        1 -> ScheduleSpec.Daily(480) // 08:00
                        2 -> ScheduleSpec.Cron("0 8 * * *")
                        3 -> ScheduleSpec.Once(System.currentTimeMillis())
                        else -> ScheduleSpec.Interval(60L)
                    }
                    onSpecChange(defaultSpec)
                }
            },
        )

        Spacer(Modifier.height(8.dp))

        when (spec) {
            is ScheduleSpec.Interval -> {
                // Keep a local draft string so the user can type freely; commit on valid parse.
                var draft by remember(spec.seconds) { mutableStateOf(spec.seconds.toString()) }
                TextField(
                    value = draft,
                    onValueChange = { v ->
                        draft = v.filter { it.isDigit() }.take(9)
                        val parsed = draft.toLongOrNull()
                        if (parsed != null && parsed > 0L) {
                            onSpecChange(ScheduleSpec.Interval(parsed))
                        }
                    },
                    label = "间隔（秒）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ScheduleSpec.Daily -> {
                var draft by remember(spec.minuteOfDay) { mutableStateOf(minuteOfDayToHhmm(spec.minuteOfDay)) }
                TextField(
                    value = draft,
                    onValueChange = { v ->
                        draft = v
                        val parsed = hhmmToMinuteOfDay(v)
                        if (parsed != null) {
                            onSpecChange(ScheduleSpec.Daily(parsed))
                        }
                    },
                    label = "执行时间（HH:MM）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ScheduleSpec.Cron -> {
                TextField(
                    value = spec.expr,
                    onValueChange = { v -> onSpecChange(ScheduleSpec.Cron(v)) },
                    label = "Cron 表达式（5 字段，本地时区）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ScheduleSpec.Once -> {
                var draft by remember(spec.epochMillis) { mutableStateOf(spec.epochMillis.toString()) }
                TextField(
                    value = draft,
                    onValueChange = { v ->
                        draft = v.filter { it.isDigit() }.take(16)
                        val parsed = draft.toLongOrNull()
                        if (parsed != null) {
                            onSpecChange(ScheduleSpec.Once(parsed))
                        }
                    },
                    label = "执行时间（Unix 毫秒时间戳）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            null -> Unit
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TriggerContextHint
// ─────────────────────────────────────────────────────────────────────────────

private data class TriggerVar(val name: String, val description: String)

private fun triggerVars(trigger: WorkflowTrigger?): List<TriggerVar> = when (trigger) {
    is WorkflowTrigger.NewMessage -> listOf(
        TriggerVar("trigger.sender", "发送人 wxid"),
        TriggerVar("trigger.talker", "会话 ID（私聊 = 对方 wxid，群 = roomid）"),
        TriggerVar("trigger.content", "消息文本内容"),
        TriggerVar("trigger.msgType", "消息类型编号"),
        TriggerVar("trigger.msgSvrId", "消息服务器 ID"),
        TriggerVar("trigger.isGroup", "是否来自群聊（\"true\"/\"false\"）"),
    )
    is WorkflowTrigger.NewMoment -> listOf(
        TriggerVar("trigger.sender", "发布人 wxid"),
        TriggerVar("trigger.content", "朋友圈文字内容"),
        TriggerVar("trigger.snsId", "朋友圈动态 ID"),
        TriggerVar("trigger.type", "动态类型编号"),
    )
    is WorkflowTrigger.NewTransfer -> listOf(
        TriggerVar("trigger.sender", "转账人 wxid"),
        TriggerVar("trigger.amount", "转账金额（分）"),
        TriggerVar("trigger.currency", "货币单位"),
        TriggerVar("trigger.note", "转账备注"),
        TriggerVar("trigger.msgSvrId", "消息服务器 ID"),
    )
    is WorkflowTrigger.NewRedPacket -> listOf(
        TriggerVar("trigger.sender", "发红包人 wxid"),
        TriggerVar("trigger.groupId", "群 ID（私聊为空字符串）"),
        TriggerVar("trigger.senderName", "发红包人昵称"),
        TriggerVar("trigger.msgSvrId", "消息服务器 ID"),
    )
    is WorkflowTrigger.Schedule -> listOf(
        TriggerVar("trigger.firedAt", "触发时间（ISO-8601 字符串）"),
    )
    null -> emptyList()
}

/**
 * Dismissible card that lists the `trigger.*` variables available for the current
 * trigger type. Resets dismissed state whenever [trigger] changes.
 */
@Composable
fun TriggerContextHint(trigger: WorkflowTrigger?) {
    val vars = triggerVars(trigger)
    if (vars.isEmpty()) return

    // Re-show the hint each time the trigger type changes.
    key(triggerToIndex(trigger)) {
        var dismissed by remember { mutableStateOf(false) }
        if (dismissed) return@key

        Card(modifier = Modifier.padding(bottom = 6.dp)) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                SmallTitle("可用变量")
                vars.forEach { v ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(
                            text = v.name,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = v.description,
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.subtitle,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    text = "知道了",
                    onClick = { dismissed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Parses a comma-separated wxid string to a nullable list (null when blank). */
private fun String.toWxidList(): List<String>? =
    split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
