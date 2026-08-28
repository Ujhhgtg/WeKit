package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import dev.ujhhgtg.wekit.R
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
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Trigger management (§ triggers). Lists global and per-session triggers, lets the user enable/
 * disable/delete them and create/edit any of the three types (schedule / message / SQL) with their
 * conditions, buffering, cooldown, and prompt template. Backed by the reactive trigger table so
 * changes made here (or by the agent's trigger-* tools) reflect live.
 */
@Composable
fun TriggersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
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

    AgentSettingsScaffold(title = stringResource(R.string.agent_triggers_title), onBack = onBack) {
        if (triggers.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_triggers_title),
                    message = stringResource(R.string.agent_empty_triggers_message),
                    actionLabel = stringResource(R.string.agent_add_trigger),
                    onAction = { editing = null; showEditor = true },
                )
            }
        } else {
            if (global.isNotEmpty()) {
                item {
                    SegmentedColumn(title = stringResource(R.string.agent_global_triggers)) {
                        global.forEach { t ->
                            item(key = t.id) {
                                TriggerSwitchRow(
                                    t,
                                    sessionTitle = null,
                                    onEdit = { editing = t; showEditor = true },
                                    scope = scope,
                                )
                            }
                        }
                    }
                }
            }

            if (perSession.isNotEmpty()) {
                item {
                    SegmentedColumn(title = stringResource(R.string.agent_session_triggers)) {
                        perSession.forEach { t ->
                            item(key = t.id) {
                                val sessionTitle = sessions[t.sessionId]?.title
                                    ?: stringResource(R.string.agent_deleted_session)
                                TriggerSwitchRow(
                                    t,
                                    sessionTitle = sessionTitle,
                                    onEdit = { editing = t; showEditor = true },
                                    scope = scope,
                                )
                            }
                        }
                    }
                }
            }

            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_trigger),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = { editing = null; showEditor = true },
                    )
                }
            }
        }
    }

    TriggerEditorSheet(
        show = showEditor,
        existing = editing,
        sessions = sessions,
        // Clear [editing] too: the editor's field state is keyed on it, so leaving it set would keep
        // the abandoned edits alive and re-show them the next time the same trigger is opened.
        onDismiss = { showEditor = false; editing = null },
        onSave = { built ->
            scope.launch {
                try {
                    WeAgentRepository.upsertTrigger(built)
                    showEditor = false
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_save_failed, e.message))
                }
            }
        },
        onDelete = { id ->
            scope.launch {
                try {
                    WeAgentRepository.deleteTrigger(id)
                    showEditor = false
                    editing = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        },
    )
}

@Composable
private fun TriggerSwitchRow(
    t: TriggerEntity,
    sessionTitle: String?,
    onEdit: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val sessionSummary = sessionTitle?.let { stringResource(R.string.agent_trigger_session_summary, it) }
    val summary = listOfNotNull(typeLabel(t.type), sessionSummary, configSummary(t)).joinToString(" · ")
    SwitchWidget(
        title = t.name.ifBlank { stringResource(R.string.agent_unnamed_trigger) },
        description = summary,
        checked = t.enabled,
        onCheckedChange = { on -> scope.launch { WeAgentRepository.setTriggerEnabled(t.id, on) } },
        onClick = onEdit,
        trailingDivider = true,
    )
}

@Composable
private fun typeLabel(type: TriggerType): String = stringResource(
    when (type) {
        TriggerType.SCHEDULE -> R.string.agent_trigger_type_schedule
        TriggerType.MESSAGE -> R.string.agent_trigger_type_message
        TriggerType.SQL -> R.string.agent_trigger_type_database
    }
)

@Composable
private fun configSummary(t: TriggerEntity): String = when (t.type) {
    TriggerType.SCHEDULE -> when (t.scheduleKind) {
        ScheduleKind.INTERVAL -> stringResource(R.string.agent_trigger_interval_summary, t.intervalSeconds ?: 0)
        ScheduleKind.DAILY -> {
            val m = t.dailyMinuteOfDay ?: 0
            val time = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
            stringResource(R.string.agent_trigger_daily_summary, time)
        }

        ScheduleKind.CRON -> stringResource(R.string.agent_trigger_cron_summary, t.cronExpr.orEmpty())
        ScheduleKind.ONCE -> stringResource(R.string.agent_trigger_once_summary)
        null -> stringResource(R.string.agent_trigger_not_configured)
    }

    TriggerType.MESSAGE, TriggerType.SQL -> {
        val debounce = t.bufferDebounceMillis / 1000
        stringResource(R.string.agent_trigger_buffer_summary, debounce, t.bufferMaxEvents)
    }
}

/**
 * Create/edit sheet. When [existing] is null a new GLOBAL trigger is created (session triggers are
 * created by the agent or bound implicitly; the settings UI creates global ones). Type is fixed once
 * created (editing keeps the same type).
 */
@Composable
private fun TriggerEditorSheet(
    show: Boolean,
    existing: TriggerEntity?,
    sessions: Map<String, SessionEntity>,
    onDismiss: () -> Unit,
    onSave: (TriggerEntity) -> Unit,
    onDelete: (String) -> Unit,
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
    // Ordered session list for the picker; preselect the trigger's bound session if any. The index is
    // keyed on the session *ids*, not on [sessionList]: any background turn touching the sessions
    // table re-emits the flow with a fresh list instance, which would otherwise silently reset the
    // user's pick. Only sessions appearing/disappearing should invalidate it.
    val sessionList = remember(sessions) { sessions.values.toList() }
    var boundSessionIndex by remember(existing, sessions.keys) {
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

    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }

    // An INTERVAL schedule with a blank/zero interval is silently dropped by TriggerScheduler
    // (it needs intervalSeconds > 0), so the trigger would look enabled but never fire.
    val intervalOk = type != TriggerType.SCHEDULE || kind != ScheduleKind.INTERVAL ||
            (intervalSeconds.toLongOrNull() ?: 0L) > 0L
    // An empty sqlOps list is the "all three ops" sentinel in TriggerConditions, so unticking
    // everything would produce a trigger firing on every DB write — the opposite of the intent.
    val sqlOpsOk = type != TriggerType.SQL || opInsert || opUpdate || opQuery
    val saveEnabled = name.isNotBlank() && promptTemplate.isNotBlank() &&
            (selectedScope == TriggerScope.GLOBAL || sessionList.isNotEmpty()) &&
            intervalOk && sqlOpsOk

    AgentEditorSheet(
        show = show,
        title = stringResource(if (creating) R.string.agent_add_trigger else R.string.agent_edit_trigger),
        onDismiss = onDismiss,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!creating) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
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
                    enabled = saveEnabled,
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.agent_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        DropDownMenuWidget(
            icon = null,
            iconPlaceholder = false,
            title = stringResource(R.string.agent_trigger_type),
            description = null,
            value = type,
            options = typeOptions.map {
                DropdownOption(
                    it,
                    stringResource(
                        when (it) {
                            TriggerType.SCHEDULE -> R.string.agent_trigger_type_schedule
                            TriggerType.MESSAGE -> R.string.agent_trigger_type_message
                            TriggerType.SQL -> R.string.agent_trigger_type_database_operation
                        }
                    ),
                )
            },
            enabled = creating,
            onValueChange = { newType -> typeIndex = typeOptions.indexOf(newType) },
        )

        DropDownMenuWidget(
            icon = null,
            iconPlaceholder = false,
            title = stringResource(R.string.agent_trigger_scope),
            description = null,
            value = selectedScope,
            options = scopeOptions.map {
                DropdownOption(
                    it,
                    stringResource(
                        if (it == TriggerScope.SESSION) R.string.agent_trigger_scope_session
                        else R.string.agent_trigger_scope_global
                    ),
                )
            },
            onValueChange = { newScope -> scopeIndex = scopeOptions.indexOf(newScope) },
        )
        if (selectedScope == TriggerScope.SESSION) {
            if (sessionList.isEmpty()) {
                Text(stringResource(R.string.agent_no_sessions_to_bind), Modifier.padding(vertical = 8.dp))
            } else {
                DropDownMenuWidget(
                    icon = null,
                    iconPlaceholder = false,
                    title = stringResource(R.string.agent_bind_to_session),
                    description = null,
                    value = boundSessionIndex.coerceIn(0, sessionList.lastIndex),
                    options = sessionList.mapIndexed { index, s ->
                        DropdownOption(index, s.title.ifBlank { stringResource(R.string.agent_unnamed_session) })
                    },
                    onValueChange = { boundSessionIndex = it },
                )
            }
        }

        when (type) {
            TriggerType.SCHEDULE -> {
                DropDownMenuWidget(
                    icon = null,
                    iconPlaceholder = false,
                    title = stringResource(R.string.agent_schedule_method),
                    description = null,
                    value = kind,
                    options = scheduleKinds.map {
                        DropdownOption(
                            it,
                            stringResource(
                                when (it) {
                                    ScheduleKind.INTERVAL -> R.string.agent_schedule_interval
                                    ScheduleKind.DAILY -> R.string.agent_schedule_daily
                                    ScheduleKind.CRON -> R.string.agent_schedule_cron
                                    ScheduleKind.ONCE -> R.string.agent_schedule_once
                                }
                            ),
                        )
                    },
                    onValueChange = { newKind -> kindIndex = scheduleKinds.indexOf(newKind) },
                )
                when (kind) {
                    ScheduleKind.INTERVAL -> NumberField(stringResource(R.string.agent_interval_seconds), intervalSeconds) { intervalSeconds = it }
                    ScheduleKind.DAILY -> Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_hour_range), dailyHour) { dailyHour = it } }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_minute_range), dailyMinute) { dailyMinute = it } }
                    }

                    ScheduleKind.CRON -> {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cronExpr,
                            onValueChange = { cronExpr = it },
                            label = { Text(stringResource(R.string.agent_cron_field)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    ScheduleKind.ONCE -> {
                        Text(stringResource(R.string.agent_once_schedule_help), Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            TriggerType.MESSAGE -> {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contentRegex,
                    onValueChange = { contentRegex = it },
                    label = { Text(stringResource(R.string.agent_message_content_regex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = talkerRegex,
                    onValueChange = { talkerRegex = it },
                    label = { Text(stringResource(R.string.agent_talker_regex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = msgTypes,
                    onValueChange = { msgTypes = it },
                    label = { Text(stringResource(R.string.agent_message_type_codes)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropDownMenuWidget(
                    icon = null,
                    iconPlaceholder = false,
                    title = stringResource(R.string.agent_message_direction),
                    description = null,
                    value = directions[directionIndex],
                    options = directions.map {
                        DropdownOption(
                            it,
                            stringResource(
                                when (it) {
                                    MessageDirection.RECEIVED -> R.string.agent_direction_received
                                    MessageDirection.SENT -> R.string.agent_direction_sent
                                    MessageDirection.BOTH -> R.string.agent_direction_both
                                }
                            ),
                        )
                    },
                    onValueChange = { newDirection -> directionIndex = directions.indexOf(newDirection) },
                )
                SwitchWidget(
                    title = stringResource(R.string.agent_filter_own_messages),
                    description = stringResource(R.string.agent_filter_own_messages_summary),
                    checked = filterOwn,
                    onCheckedChange = { filterOwn = it },
                )
                BufferFields(
                    debounceSec, maxEvents, maxWaitSec, cooldownSec,
                    { debounceSec = it }, { maxEvents = it }, { maxWaitSec = it }, { cooldownSec = it })
            }

            TriggerType.SQL -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = opInsert,
                        onClick = { opInsert = !opInsert },
                        label = { Text("INSERT") },
                    )
                    FilterChip(
                        selected = opUpdate,
                        onClick = { opUpdate = !opUpdate },
                        label = { Text("UPDATE") },
                    )
                    FilterChip(
                        selected = opQuery,
                        onClick = { opQuery = !opQuery },
                        label = { Text("QUERY") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tableRegex,
                    onValueChange = { tableRegex = it },
                    label = { Text(stringResource(R.string.agent_table_regex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sqlRegex,
                    onValueChange = { sqlRegex = it },
                    label = { Text(stringResource(R.string.agent_sql_regex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = valuesRegex,
                    onValueChange = { valuesRegex = it },
                    label = { Text(stringResource(R.string.agent_values_regex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                BufferFields(
                    debounceSec, maxEvents, maxWaitSec, cooldownSec,
                    { debounceSec = it }, { maxEvents = it }, { maxWaitSec = it }, { cooldownSec = it })
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = promptTemplate,
            onValueChange = { promptTemplate = it },
            label = { Text(stringResource(R.string.agent_trigger_prompt)) },
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        if (!intervalOk) {
            Text(stringResource(R.string.agent_interval_must_be_positive), Modifier.padding(vertical = 4.dp))
        }
        if (!sqlOpsOk) {
            Text(stringResource(R.string.agent_select_database_operation), Modifier.padding(vertical = 4.dp))
        }
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_trigger_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            onDelete(existing!!.id)
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(7)) },
        label = { Text(label) },
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
        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_debounce_seconds), debounceSec, onDebounce) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_event_limit), maxEvents, onMax) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_max_wait_seconds), maxWaitSec, onWait) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { NumberField(stringResource(R.string.agent_cooldown_seconds), cooldownSec, onCooldown) }
    }
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
