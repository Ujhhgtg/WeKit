package dev.ujhhgtg.wekit.workflow.trigger

import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.engine.TriggerContext
import dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine
import dev.ujhhgtg.wekit.workflow.model.ScheduleSpec
import dev.ujhhgtg.wekit.workflow.model.Workflow
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages coroutine-based timer jobs for [WorkflowTrigger.Schedule] workflows.
 *
 * When [start] is called, the scheduler observes the workflow repository
 * and keeps one coroutine job per active schedule workflow, cancelling
 * stale jobs and launching new ones as the list changes.
 */
class WorkflowScheduler(private val scope: CoroutineScope) {

    private val TAG = "WorkflowScheduler"
    private val jobs = ConcurrentHashMap<String, Job>()
    private val ISO = DateTimeFormatter.ISO_INSTANT

    fun start() {
        scope.launch {
            WorkflowRepository.observeAll()
                .distinctUntilChangedBy { list -> list.map { it.id to it.enabled } }
                .collect { allWorkflows ->
                    resync(allWorkflows)
                }
        }
    }

    private fun resync(allWorkflows: List<Workflow>) {
        val activeIds = allWorkflows
            .filter { it.enabled && it.trigger is WorkflowTrigger.Schedule }
            .map { it.id }
            .toSet()

        // Cancel removed/disabled workflows
        jobs.keys.filter { it !in activeIds }.forEach { id ->
            jobs.remove(id)?.cancel()
            WeLogger.d(TAG, "cancelled schedule job for workflow $id")
        }

        // Start new schedule workflows
        allWorkflows.forEach { wf ->
            if (wf.enabled && wf.trigger is WorkflowTrigger.Schedule && !jobs.containsKey(wf.id)) {
                jobs[wf.id] = scope.launch {
                    runSchedule(wf, wf.trigger.spec)
                }
                WeLogger.d(TAG, "started schedule job for workflow '${wf.name}' (${wf.trigger.spec})")
            }
        }
    }

    private suspend fun runSchedule(workflow: Workflow, spec: ScheduleSpec) {
        when (spec) {
            is ScheduleSpec.Interval -> {
                val intervalMs = spec.seconds * 1000L
                while (true) {
                    delay(intervalMs)
                    fire(workflow)
                }
            }
            is ScheduleSpec.Daily -> {
                while (true) {
                    val nowMs = System.currentTimeMillis()
                    val nextMs = nextDailyFireMillis(spec.minuteOfDay)
                    delay(nextMs - nowMs)
                    fire(workflow)
                }
            }
            is ScheduleSpec.Cron -> {
                while (true) {
                    val waitMs = nextCronFireMillis(spec.expr) - System.currentTimeMillis()
                    if (waitMs > 0) delay(waitMs)
                    fire(workflow)
                    delay(61_000L) // avoid double-firing within the same minute
                }
            }
            is ScheduleSpec.Once -> {
                val waitMs = spec.epochMillis - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)
                fire(workflow)
                jobs.remove(workflow.id)
            }
        }
    }

    private suspend fun fire(workflow: Workflow) {
        val firedAt = ISO.format(Instant.now())
        val ctx = TriggerContext.schedule(firedAt)
        WeLogger.d(TAG, "firing schedule workflow '${workflow.name}' at $firedAt")
        // Re-load from DB to get the latest version (user may have edited it)
        val current = WorkflowRepository.getById(workflow.id) ?: return
        if (!current.enabled) return
        WorkflowEngine.execute(current, ctx)
    }

    // ── Scheduling helpers ────────────────────────────────────────────────────

    private fun nextDailyFireMillis(minuteOfDay: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val target = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val todayFire = now.toLocalDate().atTime(target)
        val candidate = if (now.toLocalTime().isBefore(target)) todayFire else todayFire.plusDays(1)
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Minimal 5-field cron parser (minute hour dom month dow).
     * Only supports `*`, specific integers, and `*\/N` step syntax.
     * Returns epoch-millis of the next matching minute (at least 1 min ahead).
     */
    private fun nextCronFireMillis(expr: String): Long {
        val zone = ZoneId.systemDefault()
        var candidate = LocalDateTime.now(zone).plusMinutes(1).withSecond(0).withNano(0)
        val parts = expr.trim().split("\\s+".toRegex())
        if (parts.size != 5) return candidate.atZone(zone).toInstant().toEpochMilli()

        fun matches(field: String, value: Int, min: Int, max: Int): Boolean {
            if (field == "*") return true
            if (field.startsWith("*/")) {
                val step = field.drop(2).toIntOrNull() ?: return false
                return (value - min) % step == 0
            }
            return field.toIntOrNull() == value
        }

        for (i in 0 until 525_600) { // max 1 year search
            val ldt = candidate
            val minute = ldt.minute
            val hour = ldt.hour
            val dom = ldt.dayOfMonth
            val month = ldt.monthValue
            val dow = ldt.dayOfWeek.value % 7  // 0=Sunday..6=Saturday
            if (matches(parts[0], minute, 0, 59) &&
                matches(parts[1], hour, 0, 23) &&
                matches(parts[2], dom, 1, 31) &&
                matches(parts[3], month, 1, 12) &&
                matches(parts[4], dow, 0, 6)
            ) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
            candidate = candidate.plusMinutes(1)
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }
}
