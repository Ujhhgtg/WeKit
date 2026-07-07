package dev.ujhhgtg.wekit.agent.trigger

import dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process scheduler for [TriggerType.SCHEDULE] triggers (per the user's decision: purely
 * process-internal — no AlarmManager, so schedules that would have fired while WeChat's process was
 * dead are simply missed and recomputed from "now" on the next launch).
 *
 * One coroutine per enabled schedule trigger sleeps until its next fire time, invokes [onFire], then
 * (for repeating kinds) recomputes and sleeps again. ONCE triggers disable themselves after firing.
 * [resync] reconciles the running coroutines with the current DB rows and is called on init and after
 * any trigger create/update/delete.
 */
class TriggerScheduler(
    private val scope: CoroutineScope,
    /** Fires the trigger. Returns the (possibly reloaded) trigger's lastFiredAt handling to the manager. */
    private val onFire: suspend (TriggerEntity) -> Unit,
    /** Disables a trigger row (used for ONCE after firing, and for malformed schedules). */
    private val onDisable: suspend (triggerId: String) -> Unit,
) {
    private companion object {
        const val TAG = "TriggerScheduler"
    }

    // triggerId -> its sleeping/firing coroutine.
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Reconciles running schedule jobs against [triggers]: cancels jobs for triggers that are gone or
     * no longer eligible, and (re)starts jobs for eligible ones. Called with the FULL trigger list;
     * non-schedule / disabled rows are ignored here.
     */
    @Synchronized
    fun resync(triggers: List<TriggerEntity>) {
        val eligible = triggers.filter { it.type == TriggerType.SCHEDULE && it.enabled }
            .associateBy { it.id }

        // Cancel jobs whose trigger disappeared or became ineligible.
        val stale = jobs.keys.filter { it !in eligible }
        for (id in stale) jobs.remove(id)?.cancel()

        // (Re)start a fresh job for each eligible trigger. We always restart so an edited interval /
        // cron / time takes effect immediately (the old job is replaced).
        for ((id, trigger) in eligible) {
            jobs.remove(id)?.cancel()
            jobs[id] = scope.launch { runSchedule(trigger) }
        }
    }

    /** Cancels all schedule jobs (e.g. on teardown). */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun runSchedule(trigger: TriggerEntity) {
        // Repeating kinds loop; ONCE runs once and disables.
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val next = nextFire(trigger, now)
            if (next == null) {
                WeLogger.w(TAG, "trigger ${trigger.id} (${trigger.name}) has no valid next fire; disabling")
                onDisable(trigger.id)
                return
            }
            val wait = next - now
            if (wait > 0) delay(wait)

            // Re-check we weren't cancelled during the sleep.
            if (!scope.isActive) return

            runCatching { onFire(trigger) }
                .onFailure { WeLogger.e(TAG, "schedule fire failed for ${trigger.id}", it) }

            if (trigger.scheduleKind == ScheduleKind.ONCE) {
                onDisable(trigger.id)
                return
            }
            // For INTERVAL we advance from the actual fire time (now-ish) rather than the stored
            // lastFiredAt, so drift doesn't accumulate; recompute at loop top using System time.
        }
    }

    /**
     * Computes the next fire time (epoch millis) strictly in the future for [trigger] relative to
     * [now], or null if the schedule is malformed / already elapsed for ONCE.
     */
    private fun nextFire(trigger: TriggerEntity, now: Long): Long? = when (trigger.scheduleKind) {
        ScheduleKind.INTERVAL -> {
            val secs = trigger.intervalSeconds?.takeIf { it > 0 } ?: return null
            now + secs * 1000
        }
        ScheduleKind.DAILY -> {
            val minuteOfDay = trigger.dailyMinuteOfDay?.takeIf { it in 0..1439 } ?: return null
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                set(Calendar.MINUTE, minuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.timeInMillis
        }
        ScheduleKind.CRON -> {
            val expr = trigger.cronExpr ?: return null
            CronSchedule.nextAfter(expr, now)
        }
        ScheduleKind.ONCE -> {
            val at = trigger.atEpochMillis ?: return null
            at.takeIf { it > now }
        }
        null -> null
    }
}
