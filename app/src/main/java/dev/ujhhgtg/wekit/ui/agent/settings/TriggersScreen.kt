package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.agent.trigger.MessageDirection
import dev.ujhhgtg.wekit.agent.trigger.ScheduleKind
import dev.ujhhgtg.wekit.agent.trigger.SqlOp
import dev.ujhhgtg.wekit.agent.trigger.TriggerConditions
import dev.ujhhgtg.wekit.agent.trigger.TriggerConditionsJson
import dev.ujhhgtg.wekit.agent.trigger.TriggerScope
import dev.ujhhgtg.wekit.agent.trigger.TriggerType
import dev.ujhhgtg.wekit.ui.content.MiuixSmallTitle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Trigger management (§ triggers). Lists global and per-session triggers, lets the user enable/
 * disable/delete them and create/edit any of the three types (schedule / message / SQL) with their
 * conditions, buffering, cooldown, and prompt template. Backed by the reactive trigger table so
 * changes made here (or by the agent's trigger-* tools) reflect live.
 */
@Composable
fun TriggersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val triggers by remember { WeAgentRepository.observeTriggers() }
        .collectAsState(initial = emptyList())
    // Session id -> title, for showing which session a SESSION-scoped trigger belongs to.
    val sessions by remember {
        WeAgentRepository.observeSessions().map { list -> list.associateBy { it.id } }
    }.collectAsState(initial = emptyMap<String, SessionEntity>())

    var editing by remember { mutableStateOf<TriggerEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val global = triggers.filter { it.scope == TriggerScope.GLOBAL }
    val perSession = triggers.filter { it.scope == TriggerScope.SESSION }

    AgentSettingsScaffold(title = "触发器", onBack = onBack) {
        if (triggers.isEmpty()) {
            item { EmptyHint("还没有触发器。触发器可在定时、收到新消息或检测到数据库操作时自动唤起 AI 运行一轮。全局触发器每次触发都会新建会话。") }
        }

        if (global.isNotEmpty()) {
            item { MiuixSmallTitle("全局触发器") }
            items(global.size, key = { global[it].id }) { i ->
                TriggerCard(global[i], sessionTitle = null, scope = scope,
                    onEdit = { editing = global[i]; showEditor = true })
            }
        }

        if (perSession.isNotEmpty()) {
            item { MiuixSmallTitle("会话触发器") }
            items(perSession.size, key = { perSession[it].id }) { i ->
                val t = perSession[i]
                TriggerCard(t, sessionTitle = sessions[t.sessionId]?.title ?: "（会话已删除）", scope = scope,
                    onEdit = { editing = t; showEditor = true })
            }
        }

        item {
            Button(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text("添加触发器") }
        }
    }

    if (showEditor) {
        TriggerEditorDialog(
            existing = editing,
            sessions = sessions,
            onDismiss = { showEditor = false },
            onSave = { built ->
                scope.launch {
                    WeAgentRepository.upsertTrigger(built)
                    showEditor = false
                }
            },
        )
    }
}

@Composable
private fun TriggerCard(
    t: TriggerEntity,
    sessionTitle: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onEdit: () -> Unit,
) {
    Card(Modifier.padding(bottom = 6.dp)) {
        SwitchPreference(
            title = t.name.ifBlank { "(未命名)" },
            summary = buildString {
                append(typeLabel(t.type))
                sessionTitle?.let { append(" · 会话：").append(it) }
                append(" · ").append(configSummary(t))
            },
            checked = t.enabled,
            onCheckedChange = { on -> scope.launch { WeAgentRepository.setTriggerEnabled(t.id, on) } },
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(text = "编辑", onClick = onEdit, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = "删除",
                onClick = { scope.launch { WeAgentRepository.deleteTrigger(t.id) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun typeLabel(type: TriggerType): String = when (type) {
    TriggerType.SCHEDULE -> "定时"
    TriggerType.MESSAGE -> "新消息"
    TriggerType.SQL -> "数据库"
}

private fun configSummary(t: TriggerEntity): String = when (t.type) {
    TriggerType.SCHEDULE -> when (t.scheduleKind) {
        ScheduleKind.INTERVAL -> "每 ${t.intervalSeconds ?: 0} 秒"
        ScheduleKind.DAILY -> {
            val m = t.dailyMinuteOfDay ?: 0
            "每天 ${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
        }
        ScheduleKind.CRON -> "cron: ${t.cronExpr}"
        ScheduleKind.ONCE -> "一次性"
        null -> "未配置"
    }
    TriggerType.MESSAGE, TriggerType.SQL -> {
        val debounce = t.bufferDebounceMillis / 1000
        "缓冲 ${debounce}s / 上限 ${t.bufferMaxEvents} 条"
    }
}

/**
 * Create/edit dialog. When [existing] is null a new GLOBAL trigger is created (session triggers are
 * created by the agent or bound implicitly; the settings UI creates global ones). Type is fixed once
 * created (editing keeps the same type). The dialog scrolls since it can get tall.
 */
@Composable
private fun TriggerEditorDialog(
    existing: TriggerEntity?,
    sessions: Map<String, SessionEntity>,
    onDismiss: () -> Unit,
    onSave: (TriggerEntity) -> Unit,
) {
    val creating = existing == null

    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var promptTemplate by remember(existing) { mutableStateOf(existing?.promptTemplate.orEmpty()) }

    // Scope selector: GLOBAL (new session per fire) vs SESSION (bound to a chosen chat). Lets the user
    // fix a mistakenly-global trigger by re-binding it to a session, without deleting + recreating.
    val scopeOptions = listOf(TriggerScope.SESSION, TriggerScope.GLOBAL)
    var scopeIndex by remember(existing) {
        mutableStateOf(scopeOptions.indexOf(existing?.scope ?: TriggerScope.GLOBAL).coerceAtLeast(0))
    }
    val selectedScope = scopeOptions[scopeIndex]
    // Ordered session list for the picker; preselect the trigger's bound session if any.
    val sessionList = remember(sessions) { sessions.values.toList() }
    var boundSessionIndex by remember(existing, sessionList) {
        mutableStateOf(sessionList.indexOfFirst { it.id == existing?.sessionId }.coerceAtLeast(0))
    }

    // Type selector (only editable when creating).
    val typeOptions = listOf(TriggerType.SCHEDULE, TriggerType.MESSAGE, TriggerType.SQL)
    var typeIndex by remember(existing) {
        mutableStateOf(typeOptions.indexOf(existing?.type ?: TriggerType.SCHEDULE).coerceAtLeast(0))
    }
    val type = typeOptions[typeIndex]

    // --- schedule fields ---
    val scheduleKinds = listOf(ScheduleKind.INTERVAL, ScheduleKind.DAILY, ScheduleKind.CRON, ScheduleKind.ONCE)
    var kindIndex by remember(existing) {
        mutableStateOf(scheduleKinds.indexOf(existing?.scheduleKind ?: ScheduleKind.INTERVAL).coerceAtLeast(0))
    }
    val kind = scheduleKinds[kindIndex]
    var intervalSeconds by remember(existing) { mutableStateOf((existing?.intervalSeconds ?: 3600).toString()) }
    var dailyHour by remember(existing) { mutableStateOf(((existing?.dailyMinuteOfDay ?: 540) / 60).toString()) }
    var dailyMinute by remember(existing) { mutableStateOf(((existing?.dailyMinuteOfDay ?: 540) % 60).toString()) }
    var cronExpr by remember(existing) { mutableStateOf(existing?.cronExpr ?: "0 9 * * *") }

    // --- event conditions ---
    val cond = remember(existing) { TriggerConditionsJson.decode(existing?.conditionsJson) }
    var contentRegex by remember(existing) { mutableStateOf(cond.contentRegex.orEmpty()) }
    var talkerRegex by remember(existing) { mutableStateOf(cond.talkerRegex.orEmpty()) }
    var msgTypes by remember(existing) { mutableStateOf(cond.msgTypes?.joinToString(",").orEmpty()) }
    val directions = listOf(MessageDirection.RECEIVED, MessageDirection.SENT, MessageDirection.BOTH)
    var directionIndex by remember(existing) { mutableStateOf(directions.indexOf(cond.direction).coerceAtLeast(0)) }
    var tableRegex by remember(existing) { mutableStateOf(cond.tableRegex.orEmpty()) }
    var sqlRegex by remember(existing) { mutableStateOf(cond.sqlRegex.orEmpty()) }
    var valuesRegex by remember(existing) { mutableStateOf(cond.valuesRegex.orEmpty()) }
    var opInsert by remember(existing) { mutableStateOf(cond.sqlOps.isEmpty() || SqlOp.INSERT in cond.sqlOps) }
    var opUpdate by remember(existing) { mutableStateOf(cond.sqlOps.isEmpty() || SqlOp.UPDATE in cond.sqlOps) }
    var opQuery by remember(existing) { mutableStateOf(cond.sqlOps.isEmpty() || SqlOp.QUERY in cond.sqlOps) }

    // --- buffer + anti-loop ---
    var debounceSec by remember(existing) { mutableStateOf(((existing?.bufferDebounceMillis ?: 3000) / 1000).toString()) }
    var maxEvents by remember(existing) { mutableStateOf((existing?.bufferMaxEvents ?: 20).toString()) }
    var maxWaitSec by remember(existing) { mutableStateOf(((existing?.bufferMaxWaitMillis ?: 30000) / 1000).toString()) }
    var cooldownSec by remember(existing) { mutableStateOf(((existing?.cooldownMillis ?: 0) / 1000).toString()) }
    var filterOwn by remember(existing) { mutableStateOf(existing?.filterOwnEvents ?: true) }

    WindowDialog(show = true, title = if (creating) "添加触发器" else "编辑触发器", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            TextField(value = name, onValueChange = { name = it }, label = "名称", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))

            WindowDropdownPreference(
                title = "类型",
                items = listOf("定时", "新消息", "数据库操作"),
                selectedIndex = typeIndex,
                onSelectedIndexChange = { if (creating) typeIndex = it },
            )

            WindowDropdownPreference(
                title = "作用域",
                summary = if (selectedScope == TriggerScope.SESSION) "绑定某个会话，触发时在该会话内运行" else "每次触发都新建一个会话运行",
                items = listOf("绑定会话", "全局（每次新建会话）"),
                selectedIndex = scopeIndex,
                onSelectedIndexChange = { scopeIndex = it },
            )
            if (selectedScope == TriggerScope.SESSION) {
                if (sessionList.isEmpty()) {
                    Text("还没有会话，无法绑定。请先在助手面板里创建一个会话。", Modifier.padding(vertical = 8.dp))
                } else {
                    WindowDropdownPreference(
                        title = "绑定到会话",
                        items = sessionList.map { it.title.ifBlank { "（未命名会话）" } },
                        selectedIndex = boundSessionIndex.coerceIn(0, sessionList.lastIndex),
                        onSelectedIndexChange = { boundSessionIndex = it },
                    )
                }
            }

            when (type) {
                TriggerType.SCHEDULE -> {
                    WindowDropdownPreference(
                        title = "调度方式",
                        items = listOf("固定间隔", "每天定时", "Cron 表达式", "一次性"),
                        selectedIndex = kindIndex,
                        onSelectedIndexChange = { kindIndex = it },
                    )
                    when (kind) {
                        ScheduleKind.INTERVAL -> NumberField("间隔（秒）", intervalSeconds) { intervalSeconds = it }
                        ScheduleKind.DAILY -> Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) { NumberField("时（0-23）", dailyHour) { dailyHour = it } }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) { NumberField("分（0-59）", dailyMinute) { dailyMinute = it } }
                        }
                        ScheduleKind.CRON -> {
                            TextField(value = cronExpr, onValueChange = { cronExpr = it }, label = "Cron（分 时 日 月 周）", useLabelAsPlaceholder = true, singleLine = true)
                        }
                        ScheduleKind.ONCE -> {
                            Text("一次性触发请由 AI 通过工具设定具体时间；此处保存后需在 AI 中配置触发时间。", Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                TriggerType.MESSAGE -> {
                    Spacer(Modifier.height(8.dp))
                    TextField(value = contentRegex, onValueChange = { contentRegex = it }, label = "内容匹配（正则，可空）", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = talkerRegex, onValueChange = { talkerRegex = it }, label = "会话/发送者匹配（正则，可空）", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = msgTypes, onValueChange = { msgTypes = it }, label = "消息类型码（逗号分隔，可空）", useLabelAsPlaceholder = true, singleLine = true)
                    WindowDropdownPreference(
                        title = "方向",
                        items = listOf("收到", "发出", "两者"),
                        selectedIndex = directionIndex,
                        onSelectedIndexChange = { directionIndex = it },
                    )
                    SwitchPreference(
                        title = "过滤自己发出的消息",
                        summary = "同时会挡住 AI 通过工具发出的消息，避免自触发循环",
                        checked = filterOwn,
                        onCheckedChange = { filterOwn = it },
                    )
                    BufferFields(debounceSec, maxEvents, maxWaitSec, cooldownSec,
                        { debounceSec = it }, { maxEvents = it }, { maxWaitSec = it }, { cooldownSec = it })
                }
                TriggerType.SQL -> {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        OpToggle("INSERT", opInsert) { opInsert = it }
                        OpToggle("UPDATE", opUpdate) { opUpdate = it }
                        OpToggle("QUERY", opQuery) { opQuery = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextField(value = tableRegex, onValueChange = { tableRegex = it }, label = "表名匹配（正则，INSERT/UPDATE）", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = sqlRegex, onValueChange = { sqlRegex = it }, label = "SQL 匹配（正则，QUERY）", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = valuesRegex, onValueChange = { valuesRegex = it }, label = "写入值匹配（正则，INSERT/UPDATE）", useLabelAsPlaceholder = true, singleLine = true)
                    BufferFields(debounceSec, maxEvents, maxWaitSec, cooldownSec,
                        { debounceSec = it }, { maxEvents = it }, { maxWaitSec = it }, { cooldownSec = it })
                }
            }

            Spacer(Modifier.height(8.dp))
            TextField(value = promptTemplate, onValueChange = { promptTemplate = it }, label = "提示词（触发时追加在事件时间线之后）", useLabelAsPlaceholder = true, maxLines = 6)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "保存",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    // Disable save when a session-bound trigger has no session to bind to.
                    enabled = name.isNotBlank() && promptTemplate.isNotBlank() &&
                        (selectedScope == TriggerScope.GLOBAL || sessionList.isNotEmpty()),
                    onClick = {
                        val built = buildTrigger(
                            existing = existing,
                            name = name,
                            promptTemplate = promptTemplate,
                            type = type,
                            scope = selectedScope,
                            sessionId = if (selectedScope == TriggerScope.SESSION)
                                sessionList.getOrNull(boundSessionIndex)?.id else null,
                            kind = kind,
                            intervalSeconds = intervalSeconds.toLongOrNull(),
                            dailyMinuteOfDay = (dailyHour.toIntOrNull() ?: 0) * 60 + (dailyMinute.toIntOrNull() ?: 0),
                            cronExpr = cronExpr,
                            conditions = TriggerConditions(
                                contentRegex = contentRegex.ifBlank { null },
                                talkerRegex = talkerRegex.ifBlank { null },
                                msgTypes = msgTypes.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.isNotEmpty() },
                                direction = directions[directionIndex],
                                sqlOps = buildList {
                                    if (opInsert) add(SqlOp.INSERT)
                                    if (opUpdate) add(SqlOp.UPDATE)
                                    if (opQuery) add(SqlOp.QUERY)
                                }.takeIf { it.size < 3 } ?: emptyList(),
                                tableRegex = tableRegex.ifBlank { null },
                                sqlRegex = sqlRegex.ifBlank { null },
                                valuesRegex = valuesRegex.ifBlank { null },
                            ),
                            debounceSec = debounceSec.toLongOrNull(),
                            maxEvents = maxEvents.toIntOrNull(),
                            maxWaitSec = maxWaitSec.toLongOrNull(),
                            cooldownSec = cooldownSec.toLongOrNull(),
                            filterOwn = filterOwn,
                        )
                        onSave(built)
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(7)) },
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BufferFields(
    debounceSec: String, maxEvents: String, maxWaitSec: String, cooldownSec: String,
    onDebounce: (String) -> Unit, onMax: (String) -> Unit, onWait: (String) -> Unit, onCooldown: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { NumberField("防抖（秒）", debounceSec, onDebounce) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { NumberField("上限（条）", maxEvents, onMax) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { NumberField("最长等待（秒）", maxWaitSec, onWait) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { NumberField("冷却（秒）", cooldownSec, onCooldown) }
    }
}

@Composable
private fun OpToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(
        text = if (checked) "✓ $label" else label,
        onClick = { onChange(!checked) },
        colors = if (checked) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}

/** Assembles a [TriggerEntity] from the editor fields, preserving id/scope/session for edits. */
private fun buildTrigger(
    existing: TriggerEntity?,
    name: String,
    promptTemplate: String,
    type: TriggerType,
    scope: TriggerScope,
    sessionId: String?,
    kind: ScheduleKind,
    intervalSeconds: Long?,
    dailyMinuteOfDay: Int,
    cronExpr: String,
    conditions: TriggerConditions,
    debounceSec: Long?,
    maxEvents: Int?,
    maxWaitSec: Long?,
    cooldownSec: Long?,
    filterOwn: Boolean,
): TriggerEntity {
    val base = existing?.copy(
        name = name, promptTemplate = promptTemplate, type = type,
        // Scope / session binding are now editable, so apply the chosen values (lets the user re-bind
        // a mistakenly-global trigger to a session, or move it, without recreating it).
        scope = scope, sessionId = sessionId.takeIf { scope == TriggerScope.SESSION },
    ) ?: TriggerEntity(
        id = UUID.randomUUID().toString(),
        name = name,
        type = type,
        scope = scope,
        sessionId = sessionId.takeIf { scope == TriggerScope.SESSION },
        enabled = true,
        promptTemplate = promptTemplate,
        createdAt = Instant.now(),
    )
    return when (type) {
        TriggerType.SCHEDULE -> base.copy(
            scheduleKind = kind,
            intervalSeconds = intervalSeconds.takeIf { kind == ScheduleKind.INTERVAL },
            dailyMinuteOfDay = dailyMinuteOfDay.takeIf { kind == ScheduleKind.DAILY }?.coerceIn(0, 1439),
            cronExpr = cronExpr.takeIf { kind == ScheduleKind.CRON },
            atEpochMillis = existing?.atEpochMillis?.takeIf { kind == ScheduleKind.ONCE },
            conditionsJson = null,
        )
        TriggerType.MESSAGE, TriggerType.SQL -> base.copy(
            scheduleKind = null,
            conditionsJson = TriggerConditionsJson.encode(conditions),
            bufferDebounceMillis = (debounceSec?.coerceAtLeast(0) ?: 3L) * 1000,
            bufferMaxEvents = maxEvents?.coerceAtLeast(1) ?: 20,
            bufferMaxWaitMillis = (maxWaitSec?.coerceAtLeast(1) ?: 30L) * 1000,
            cooldownMillis = (cooldownSec?.coerceAtLeast(0) ?: 0L) * 1000,
            filterOwnEvents = filterOwn,
        )
    }
}
