package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeDateTimeField
import dev.ujhhgtg.wekit.ui.content.WeDateTimeMode
import dev.ujhhgtg.wekit.ui.content.WeTimeOfDayField
import dev.ujhhgtg.wekit.ui.content.currentMinuteOfDay
import dev.ujhhgtg.wekit.ui.content.formatDateTime
import dev.ujhhgtg.wekit.ui.content.formatMinuteOfDay
import dev.ujhhgtg.wekit.ui.content.parseDateTime
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.Calendar

private const val TAG = "HideContacts.ScheduleUi"

/**
 * Week days in the order a Chinese-locale user reads them (Monday first, Sunday last), paired with
 * their chip/summary label. The `Calendar` constants are the ones [HideSchedule.daysOfWeek] stores,
 * so this list is both the chip source and the summary's sort order.
 */
private val DAY_LABELS: List<Pair<Int, String>> = listOf(
    Calendar.MONDAY to "周一",
    Calendar.TUESDAY to "周二",
    Calendar.WEDNESDAY to "周三",
    Calendar.THURSDAY to "周四",
    Calendar.FRIDAY to "周五",
    Calendar.SATURDAY to "周六",
    Calendar.SUNDAY to "周日",
)

/** One hour out, so a freshly added 单次 entry is never already in the past when it is saved. */
private const val DEFAULT_ONCE_OFFSET_MILLIS = 60L * 60L * 1000L

private fun actionLabel(action: HideScheduleAction): String =
    if (action == HideScheduleAction.HIDE) "隐藏" else "显示"

private fun kindLabel(kind: HideScheduleKind): String =
    if (kind == HideScheduleKind.REPEATING) "每周重复" else "单次"

private fun daysLabel(days: Set<Int>): String = when {
    days.isEmpty() -> "从不"
    days.containsAll(ALL_DAYS_OF_WEEK) -> "每天"
    else -> DAY_LABELS.filter { it.first in days }.joinToString(" ") { it.second }
}

/**
 * The one-line row summary: `每天 22:00 · 隐藏` / `周一 周三 09:30 · 显示` /
 * `2026-08-01 12:00:00 · 隐藏（单次）`.
 */
private fun HideSchedule.summary(): String = when (kind) {
    HideScheduleKind.REPEATING ->
        "${daysLabel(daysOfWeek)} ${formatMinuteOfDay(minuteOfDay)} · ${actionLabel(action)}"

    HideScheduleKind.ONCE ->
        "${formatDateTime(atEpochMillis)} · ${actionLabel(action)}（单次）"
}

/** The local minute-of-day [millis] falls in — the [HideSchedule.minuteOfDay] a 单次 entry fires at. */
private fun minuteOfDayAt(millis: Long): Int = Calendar.getInstance().let {
    it.timeInMillis = millis
    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
}

/** The local `Calendar.DAY_OF_WEEK` [millis] falls on. */
private fun dayOfWeekAt(millis: Long): Int = Calendar.getInstance().let {
    it.timeInMillis = millis
    it.get(Calendar.DAY_OF_WEEK)
}

/**
 * Whether [a] and [b] can come due at the very same minute.
 *
 * Two such entries are ambiguous rather than merely redundant: both alarms fire at the same instant in
 * an order `AlarmManager` doesn't define, and `HideContactsSchedule`'s startup catch-up breaks the tie
 * with `maxByOrNull`, which keeps the *first* maximum — i.e. list order. `每天 22:00 · 隐藏` next to
 * `每天 22:00 · 显示` therefore has no defined outcome, so the editor refuses to create the pair.
 *
 * [HideSchedule.enabled] is deliberately ignored on both sides: a disabled entry is one switch away
 * from being live, and letting it be saved would just move the ambiguity to the moment it's toggled
 * on — where there is no editor to complain.
 */
private fun collidesWith(a: HideSchedule, b: HideSchedule): Boolean = when {
    a.kind == HideScheduleKind.REPEATING && b.kind == HideScheduleKind.REPEATING ->
        a.minuteOfDay == b.minuteOfDay && a.daysOfWeek.any { it in b.daysOfWeek }

    a.kind == HideScheduleKind.ONCE && b.kind == HideScheduleKind.ONCE ->
        // Conservative: same minute counts as a collision. A `ONCE` alarm is armed with
        // `atEpochMillis` verbatim, so two instants inside one minute do fire in a defined order —
        // but the picker only ever produces whole minutes, so anything finer is hand-entered and
        // indistinguishable to the user reading the list.
        a.atEpochMillis / 60_000L == b.atEpochMillis / 60_000L

    else -> {
        val repeating = if (a.kind == HideScheduleKind.REPEATING) a else b
        val once = if (a.kind == HideScheduleKind.ONCE) a else b
        minuteOfDayAt(once.atEpochMillis) == repeating.minuteOfDay &&
                dayOfWeekAt(once.atEpochMillis) in repeating.daysOfWeek
    }
}

private fun newSchedule(): HideSchedule = HideSchedule(
    id = newHideScheduleId(),
    action = HideScheduleAction.HIDE,
    kind = HideScheduleKind.REPEATING,
    minuteOfDay = currentMinuteOfDay(),
    daysOfWeek = ALL_DAYS_OF_WEEK,
    atEpochMillis = System.currentTimeMillis() + DEFAULT_ONCE_OFFSET_MILLIS,
)

/**
 * The 定时显示/隐藏 CRUD list, opened from [HideContacts]'s settings dialog.
 *
 * Edits happen entirely in a Compose draft list; nothing is persisted until 确定, which hands the
 * finished list to [HideContactsSchedule.mutate] — the only write path, and the one that re-arms the
 * alarms, so a change takes effect immediately instead of at the next launch.
 *
 * The transform passed to `mutate` runs under the scheduler's monitor, so it stays a pure, allocation-
 * only reconciliation of two lists it is handed. It cannot be a plain snapshot copy: the draft is a
 * dialog-open-time photograph, and a 单次 entry that fires while the dialog is open is applied and
 * deleted by the scheduler in the meantime. Writing the draft back wholesale would resurrect it — no
 * alarm would be armed for it (its instant has passed), so it would sit there until the next process
 * start, where the startup catch-up would re-apply its action and only then delete it: a spurious
 * 显示/隐藏 flip. Hence rows the scheduler no longer knows about are dropped, except those added
 * during this dialog session, which by construction cannot be in the list `mutate` passes in. The
 * drop is not silent: 确定 compares the draft against the list `mutate` committed and, if anything
 * was dropped, logs it and shows a Toast — otherwise a user who had just edited that row would find
 * it simply gone.
 *
 * An elapsed [HideScheduleKind.ONCE] entry that *hasn't* been consumed is still listed as-is rather
 * than pruned here: expiry belongs to the scheduler (it consumes such entries on fire and on startup
 * catch-up), and silently dropping rows the user never touched would be surprising.
 */
internal fun HideContacts.showSchedulesDialog(context: Context) {
    showComposeDialog(context) {
        val schedules = remember { HideContactsSchedule.schedules.toMutableStateList() }

        // Ids created in this dialog session. Tracked explicitly rather than inferred (from the id's
        // timestamp, say — see newHideScheduleId) because the reconciliation on 确定 depends on it.
        val addedIds = remember { mutableSetOf<String>() }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text("定时显示/隐藏") },
            text = {
                DefaultColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("定时任务", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "到点自动临时显示或恢复隐藏, 不会改动隐藏列表",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                showScheduleEditor(
                                    context = context,
                                    title = "添加定时",
                                    initial = newSchedule(),
                                    others = schedules.toList(),
                                ) { added ->
                                    schedules.add(added)
                                    addedIds += added.id
                                }
                            }
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                            Text("添加")
                        }
                    }

                    if (schedules.isEmpty()) {
                        Text(
                            "暂无定时任务, 点击右上角“添加”创建",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(schedules, key = { it.id }) { schedule ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        showScheduleEditor(
                                            context = context,
                                            title = "编辑定时",
                                            initial = schedule,
                                            others = schedules.toList(),
                                        ) { edited ->
                                            val index = schedules.indexOfFirst { it.id == edited.id }
                                            if (index >= 0) schedules[index] = edited
                                        }
                                    },
                                    content = { Text(schedule.summary()) },
                                    // Only for 每周重复: a 单次 headline already ends in （单次）.
                                    supportingContent = if (schedule.kind == HideScheduleKind.REPEATING) {
                                        { Text(kindLabel(schedule.kind)) }
                                    } else null,
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(
                                                checked = schedule.enabled,
                                                onCheckedChange = { enabled ->
                                                    val index =
                                                        schedules.indexOfFirst { it.id == schedule.id }
                                                    if (index >= 0) {
                                                        schedules[index] =
                                                            schedule.copy(enabled = enabled)
                                                    }
                                                },
                                            )
                                            IconButton(
                                                onClick = { schedules.removeAll { it.id == schedule.id } }
                                            ) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = "删除定时",
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val draft = schedules.toList()
                    val added = addedIds.toSet()
                    // The sole write path: persists and re-arms every alarm in one guarded step.
                    val committed = HideContactsSchedule.mutate { stored ->
                        // Reconcile against what the scheduler holds *now*, not what it held when the
                        // dialog opened: an entry it dropped meanwhile (a fired 单次) stays dropped.
                        val storedIds = stored.mapTo(mutableSetOf()) { it.id }
                        draft.filter { it.id in storedIds || it.id in added }
                    }
                    // Dropping is correct (see the KDoc), but doing it silently is not: the user
                    // edited a row, tapped 确定, and would otherwise just find it gone. Say so, the
                    // same way every other 隐藏联系人 notice is surfaced — a Toast — and log it.
                    val committedIds = committed.mapTo(mutableSetOf()) { it.id }
                    val dropped = draft.filter { it.id !in committedIds }
                    if (dropped.isNotEmpty()) {
                        WeLogger.i(
                            TAG,
                            "dropped ${dropped.size} schedule(s) that the scheduler consumed while the " +
                                    "dialog was open: ${dropped.map { it.id }}"
                        )
                        showToast(context, "${dropped.size} 个单次定时在编辑期间已触发并自动删除, 对它们的修改未保存")
                    }
                    onDismiss()
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * The add/edit sheet for a single entry. Reports back only complete, well-formed values — 保存 stays
 * disabled, with the reason spelled out under the fields, while:
 *
 * - a 每周重复 entry has no day selected, or
 * - the 单次 timestamp doesn't parse, or
 * - the 单次 timestamp is already in the past — such an entry arms no alarm at all and would instead
 *   be applied and swallowed by the scheduler's startup catch-up, i.e. flip the state at the next
 *   launch out of nowhere. Reachable by editing an elapsed row, and by switching a long-lived 每周重复
 *   entry to 单次 (whose timestamp is the "now + 1h" stamped when it was first created). This is
 *   gated rather than silently re-defaulted on prefill, because re-defaulting only covers the
 *   prefilled paths — a past date typed or picked by hand would still slip through — and it would
 *   rewrite a value the user is looking at without saying so, or
 * - the entry can fire at the same minute as another row (see [collidesWith]).
 *
 * so the list can never hold an entry `HideContactsSchedule.schedules` would filter out, nor a pair
 * whose firing order is undefined.
 *
 * [others] is the whole draft list, this entry included; it is matched by id so an edit never collides
 * with itself.
 *
 * The 单次 timestamp is kept as text (the shape [WeDateTimeField] works in) and parsed on save, so a
 * half-typed value isn't snapped back while the user is still editing it.
 */
private fun showScheduleEditor(
    context: Context,
    title: String,
    initial: HideSchedule,
    others: List<HideSchedule>,
    onSave: (HideSchedule) -> Unit,
) {
    showComposeDialog(context) {
        var draft by remember { mutableStateOf(initial) }
        var onceText by remember {
            mutableStateOf(
                formatDateTime(
                    initial.atEpochMillis.takeIf { it > 0L }
                        ?: (System.currentTimeMillis() + DEFAULT_ONCE_OFFSET_MILLIS)
                )
            )
        }

        val onceMillis = parseDateTime(onceText)
        val isOncePast = draft.kind == HideScheduleKind.ONCE &&
                onceMillis != null && onceMillis <= System.currentTimeMillis()

        // What 保存 would hand back, or null while the 单次 timestamp doesn't parse.
        val candidate = when (draft.kind) {
            HideScheduleKind.REPEATING -> draft
            HideScheduleKind.ONCE -> onceMillis?.let { draft.copy(atEpochMillis = it) }
        }
        val conflict = candidate != null &&
                others.any { it.id != candidate.id && collidesWith(candidate, it) }

        val canSave = !conflict && when (draft.kind) {
            HideScheduleKind.REPEATING -> draft.daysOfWeek.isNotEmpty()
            HideScheduleKind.ONCE -> onceMillis != null && !isOncePast
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(title) },
            text = {
                DefaultColumn {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        HideScheduleAction.entries.forEachIndexed { index, action ->
                            SegmentedButton(
                                selected = draft.action == action,
                                onClick = { draft = draft.copy(action = action) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, HideScheduleAction.entries.size
                                ),
                            ) { Text(actionLabel(action)) }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        HideScheduleKind.entries.forEachIndexed { index, kind ->
                            SegmentedButton(
                                selected = draft.kind == kind,
                                onClick = { draft = draft.copy(kind = kind) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, HideScheduleKind.entries.size
                                ),
                            ) { Text(kindLabel(kind)) }
                        }
                    }

                    when (draft.kind) {
                        HideScheduleKind.REPEATING -> {
                            WeTimeOfDayField(
                                modifier = Modifier.fillMaxWidth(),
                                label = "时间",
                                minuteOfDay = draft.minuteOfDay,
                                onMinuteChange = { draft = draft.copy(minuteOfDay = it) },
                            )

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DAY_LABELS.forEach { (day, label) ->
                                    val selected = day in draft.daysOfWeek
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val days = draft.daysOfWeek.toMutableSet()
                                            if (selected) days -= day else days += day
                                            draft = draft.copy(daysOfWeek = days)
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }

                            if (draft.daysOfWeek.isEmpty()) {
                                Text(
                                    "请至少选择一天",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HideScheduleKind.ONCE -> {
                            WeDateTimeField(
                                modifier = Modifier.fillMaxWidth(),
                                label = "日期与时间",
                                value = onceText,
                                onValueChange = { onceText = it },
                                mode = WeDateTimeMode.DATE_TIME,
                            )
                            Text(
                                "单次任务触发后会自动删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (isOncePast) {
                                Text(
                                    "该时间已过, 请选择一个将来的时间",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (conflict) {
                        Text(
                            "已有定时任务会在同一时间触发, 请换一个时间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        // Null only for an unparseable 单次 timestamp, which `canSave` already blocks.
                        if (candidate != null) {
                            onSave(candidate)
                            onDismiss()
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            },
        )
    }
}
