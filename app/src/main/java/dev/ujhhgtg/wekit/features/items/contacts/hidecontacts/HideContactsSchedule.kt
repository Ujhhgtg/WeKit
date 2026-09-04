package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.util.Calendar

private const val TAG = "HideContacts.Schedule"

/** `Calendar.SUNDAY..Calendar.SATURDAY` — the default (and maximal) [HideSchedule.daysOfWeek]. */
val ALL_DAYS_OF_WEEK: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

/**
 * One user-defined "alarm" that flips 隐藏联系人's temporary-show state at a chosen time.
 *
 * [HideScheduleAction.SHOW] is exactly the `#show` state — it toggles [HideContacts]'s in-memory
 * `temporarilyShown` flag and **never** rewrites the hidden-contact list, so a schedule can't lose
 * the user's configuration.
 */
@Serializable
data class HideSchedule(
    /** Stable identity: the `AlarmManager` request code and the list key. See [newHideScheduleId]. */
    val id: String,
    val enabled: Boolean = true,
    val action: HideScheduleAction,
    val kind: HideScheduleKind,
    /** [HideScheduleKind.REPEATING] only: 0..1439. */
    val minuteOfDay: Int = 0,
    /** [HideScheduleKind.REPEATING] only: `Calendar.SUNDAY`..`Calendar.SATURDAY`. */
    val daysOfWeek: Set<Int> = ALL_DAYS_OF_WEEK,
    /** [HideScheduleKind.ONCE] only: the full date-time. Deleted after it fires. */
    val atEpochMillis: Long = 0L,
)

@Serializable
enum class HideScheduleAction { HIDE, SHOW }

@Serializable
enum class HideScheduleKind { REPEATING, ONCE }

/** Mirrors `ConversationGrouping`'s id scheme: monotonic and unique enough for a hand-edited list. */
fun newHideScheduleId(): String = "hsched_${System.currentTimeMillis()}"

// The install/uninstall pair, named like the other hook installers in this package.
fun HideContacts.installSchedules() = HideContactsSchedule.install()

fun HideContacts.uninstallSchedules() = HideContactsSchedule.uninstall()

/**
 * The 定时显示/隐藏 scheduler.
 *
 * ## Why AlarmManager and not a coroutine
 *
 * `dev.ujhhgtg.wekit.agent.trigger.TriggerScheduler` sleeps in-process on purpose (its triggers are
 * only meaningful while WeChat runs). This one must fire at a wall-clock time the user picked, which
 * means surviving Doze — hence `setExactAndAllowWhileIdle`. The coordination model is still
 * TriggerScheduler's: [resync] is the single reconciliation point, called on install and — via
 * [mutate], the sole external mutation entry point — after every list mutation, cancelling everything
 * before re-registering from the current list.
 *
 * ## What this cannot do, and why the startup catch-up exists
 *
 * The module cannot add a `<receiver>` to WeChat's manifest, so [AlarmReceiver] is registered at
 * runtime and therefore only exists while this process does. When an alarm fires with the main
 * process dead the broadcast lands nowhere and is simply consumed — no process is started. That is
 * precisely what [catchUp] compensates for: on every `onEnable` we look backwards for the most recent
 * fire time that has already passed and apply *that* entry's action, so the state the user asked for
 * is what they get on the next launch regardless of what the process was doing at the time.
 *
 * ## Relationship to the manual toggle
 *
 * A schedule only ever *writes* `temporarilyShown` at its fire instant. It does not lock it: `#show`,
 * `#hide` and the triple-tap gesture stay live and their result survives until the next fire time or
 * the next manual change. The catch-up runs once per process, at attach time.
 *
 * ## Accepted limitation: time-zone and clock changes
 *
 * No `ACTION_TIMEZONE_CHANGED` / `ACTION_TIME_CHANGED` receiver is registered. Alarms are armed as
 * absolute `RTC_WAKEUP` instants computed from the local zone at arm time, so a TZ change or a DST
 * transition between arming and firing makes exactly *one* fire land at the wrong local time (off by
 * the offset delta). It self-corrects immediately: [onFired] recomputes the next occurrence from the
 * clock as it is *then*, and [resync] recomputes everything on the next install. One mistimed
 * 显示/隐藏 flip after a flight or a DST switch was judged not worth a broadcast receiver whose only
 * job is to call [resync].
 */
object HideContactsSchedule {

    private const val KEY_SCHEDULES = "hide_contacts_schedules"

    /**
     * Private to WeKit and namespaced under the module's own package, so it cannot collide with any
     * of WeChat's own broadcasts. The broadcast is additionally package-restricted (see
     * [pendingIntentFor]) and the receiver is registered `NOT_EXPORTED`, so nothing outside this app
     * can send or observe it.
     */
    private const val ACTION_FIRE = "dev.ujhhgtg.wekit.action.HIDE_CONTACTS_SCHEDULE"
    private const val EXTRA_ID = "schedule_id"

    /**
     * Per-entry `data` URI. `PendingIntent` equality ignores extras and compares
     * `Intent.filterEquals` + request code, so without a distinguishing URI two entries would only be
     * told apart by `id.hashCode()`. The receiver's [IntentFilter] carries the matching scheme.
     */
    private const val URI_SCHEME = "wekit"

    private var raw by WePrefs.prefOption(KEY_SCHEDULES, "")

    /** Whether [install] has run in this process. Guards [resync] against arming a disabled feature. */
    private var installed = false
    private var receiverRegistered = false

    /**
     * Ids this process armed. Kept so [resync] can cancel entries the user deleted; entries that
     * still exist are cancelled via the current list, which also reaches alarms armed by a *previous*
     * process instance (a `PendingIntent` is a system-wide object, so rebuilding it here is enough to
     * cancel it).
     */
    private val armedIds = mutableSetOf<String>()

    // ── persistence ──────────────────────────────────────────────────────────────────────────────

    /**
     * The schedule list, sanitized. Reads parse from [WePrefs] every time (the list is tiny and only
     * touched on alarm fire / edit), so a write from the settings UI is visible to the receiver
     * immediately.
     *
     * The setter is private: [mutate] is the only mutation entry point (see its doc for why a bare
     * read-modify-write is unsafe here). Read access stays public so the settings UI can seed its
     * draft from the current list.
     */
    var schedules: List<HideSchedule>
        get() = decodeRaw().filter { it.id.isNotBlank() && isWellFormed(it) }.distinctBy { it.id }
        private set(value) {
            runCatching { raw = DefaultJson.encodeToString(value) }
                .onFailure { WeLogger.w(TAG, "failed to save schedules", it) }
        }

    /** Unsanitized parse of [raw]: every entry that's valid JSON, including ones [schedules] would
     * filter out (malformed `minuteOfDay`, blank/duplicate id). Used by callers that need to remove
     * one entry from storage without silently discarding the others (see [removeStored]). */
    private fun decodeRaw(): List<HideSchedule> {
        val text = raw
        if (text.isEmpty()) return emptyList()
        return runCatching { DefaultJson.decodeFromString<List<HideSchedule>>(text) }
            .onFailure { WeLogger.w(TAG, "failed to parse schedules, resetting", it) }
            .getOrDefault(emptyList())
    }

    private fun isWellFormed(schedule: HideSchedule): Boolean = when (schedule.kind) {
        HideScheduleKind.REPEATING -> schedule.minuteOfDay in 0..1439
        HideScheduleKind.ONCE -> schedule.atEpochMillis > 0L
    }

    /**
     * Removes exactly the entries in [ids] from the *stored* (unsanitized) list and persists the
     * rest — including any entry [schedules]' getter would otherwise have filtered out as malformed.
     * [onFired] and [catchUp] use this instead of writing back the sanitized `schedules` snapshot, so
     * a rejected entry isn't silently and permanently deleted the next time an unrelated entry fires
     * (finding #3).
     */
    private fun removeStored(ids: Set<String>) {
        if (ids.isEmpty()) return
        schedules = decodeRaw().filterNot { it.id in ids }
    }

    /**
     * The mutation entry point for the settings UI: applies [transform] to the current (sanitized)
     * list and persists + resyncs the result as one guarded step. Returns the persisted (sanitized)
     * list, so a caller — e.g. the CRUD dialog — can render the committed result directly instead of
     * re-reading [schedules] afterward and risking a stale read if another mutation lands in between.
     *
     * Why this exists instead of a plain `schedules = ...` write: the natural Compose pattern is
     * "read the list on dialog open, write the whole edited list back on commit". [onFired] does a
     * read-modify-write of its own (delete a fired `ONCE` entry) under this object's monitor, but the
     * `schedules` property itself was unsynchronized — a save that started before the fire and
     * committed after it would silently resurrect the entry [onFired] just deleted, and the next
     * [catchUp] would re-apply its action. Routing every write through this `@Synchronized` method
     * closes that window, since it shares the monitor with [onFired] and [catchUp].
     *
     * Note this overwrites the *entire* stored list with the sanitized result of [transform], unlike
     * [removeStored] which preserves malformed entries it isn't targeting — a UI commit reflects
     * everything the user saw and edited, so it's correct for it to drop what [schedules] already
     * filtered out rather than resurrect it.
     *
     * [transform] must be pure, fast and non-blocking: it runs while this object's monitor is held, so
     * a lambda that blocks — awaiting a coroutine, or waiting on a thread that itself needs this
     * monitor via [onFired] or [catchUp] — deadlocks the scheduler.
     */
    @Synchronized
    fun mutate(transform: (List<HideSchedule>) -> List<HideSchedule>): List<HideSchedule> {
        schedules = transform(schedules)
        resync()
        return schedules
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────

    /**
     * Called from `HideContacts.onEnable`, i.e. during process attach, before any Activity exists.
     * Nothing here may touch UI: [catchUp] only writes the in-memory flag and asks
     * `WeConversationApi.reloadConversations()` (which marshals itself onto the main thread) for a
     * refresh.
     */
    @Synchronized
    fun install() {
        // The state a schedule mutates (`temporarilyShown`) is main-process-only, and HideContacts
        // itself never loads anywhere else. Belt and braces so an alarm can never be armed by, say,
        // :push if the feature's process set is ever widened.
        if (!TargetProcesses.isInMain) return
        if (installed) return

        // Set first (rather than after setup succeeds) because resync() below re-checks `installed`
        // and would otherwise no-op. If anything throws — registerReceiver() is the one unguarded
        // call here — roll everything back and reset the flag, so a failed install() doesn't get
        // stuck `true` forever (every later install() call would then early-return at the guard
        // above, leaving the scheduler permanently dead for this process) while also not stranding a
        // registered receiver or armed alarms that no `installed = true` state accounts for.
        //
        // Swallowed rather than rethrown: this is called from HideContacts.onEnable(), and
        // BaseFeature.enable() treats any exception out of onEnable() as a failure of the *whole*
        // feature — it unhooks everything and flips isActive false, which would make every hidden
        // contact visible again just because this secondary scheduler failed to come up. The rollback
        // above already leaves no receiver and no armed alarm behind, so it's safe to just log and
        // move on with the scheduler inert for this process.
        installed = true
        try {
            registerReceiver()
            catchUp()
            resync()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to install schedules, rolling back", e)
            installed = false
            cancelAll()
            unregisterReceiver()
        }
    }

    /** Called from `HideContacts.onDisable`: leaves no armed `PendingIntent` behind. */
    @Synchronized
    fun uninstall() {
        if (!installed) return
        installed = false
        cancelAll()
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_FIRE).apply { addDataScheme(URI_SCHEME) }
        // ContextCompat picks RECEIVER_NOT_EXPORTED on API 33+ and drops the flag below it, which is
        // what API 34's mandatory-flag rule requires while staying valid down to min SDK 28.
        ContextCompat.registerReceiver(
            HostInfo.application, AlarmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        WeLogger.d(TAG, "registered schedule alarm receiver")
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(AlarmReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister schedule alarm receiver", it) }
    }

    // ── reconciliation ───────────────────────────────────────────────────────────────────────────

    /**
     * Cancels every alarm this process knows about and re-arms one per enabled entry. The
     * reconciliation primitive behind [install] and [mutate]; not exposed on its own — every list
     * mutation goes through [mutate], which calls this after persisting.
     */
    @Synchronized
    private fun resync() {
        if (!installed) return
        val list = schedules
        cancelAll(list.map { it.id })

        val now = System.currentTimeMillis()
        for (schedule in list) {
            if (!schedule.enabled) continue
            val next = nextFireAfter(schedule, now)
            if (next == null) {
                WeLogger.d(TAG, "schedule ${schedule.id} has no future fire time; skipped")
                continue
            }
            arm(schedule.id, next)
        }
        WeLogger.d(TAG, "resynced ${armedIds.size} schedule alarm(s)")
    }

    private fun cancelAll(extraIds: List<String> = emptyList()) {
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        for (id in armedIds + extraIds) {
            val pendingIntent = pendingIntentFor(id)
            runCatching { alarmManager.cancel(pendingIntent) }
                .onFailure { WeLogger.w(TAG, "failed to cancel alarm for $id", it) }
            pendingIntent.cancel()
        }
        armedIds.clear()
    }

    private fun arm(id: String, atEpochMillis: Long) {
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        val pendingIntent = pendingIntentFor(id)

        // WeChat declares USE_EXACT_ALARM (all inspected versions) and SCHEDULE_EXACT_ALARM (8.0.74+),
        // and we inherit its permissions by running in its process — so on Android 13+ this is always
        // allowed and the user is never prompted. The one gap is Android 12 running a build that
        // predates the SCHEDULE_EXACT_ALARM declaration, where the exact call would throw; degrade to
        // an inexact alarm there rather than losing the schedule (the startup catch-up still applies
        // the right state, and an inexact while-idle alarm is late, not absent).
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

        val armed = canBeExact && runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
        }.onFailure { WeLogger.w(TAG, "exact alarm rejected for $id, falling back to inexact", it) }
            .isSuccess

        if (!armed) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
            }.onFailure {
                WeLogger.w(TAG, "failed to arm alarm for $id", it)
                return
            }
        }
        armedIds += id
    }

    private fun pendingIntentFor(id: String): PendingIntent {
        val intent = Intent(ACTION_FIRE).apply {
            // Keeps the broadcast inside WeChat's process group; combined with RECEIVER_NOT_EXPORTED
            // no other app can see or spoof it.
            setPackage(HostInfo.packageName)
            data = "$URI_SCHEME://hidecontacts/schedule/$id".toUri()
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            HostInfo.application,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── firing ───────────────────────────────────────────────────────────────────────────────────

    private object AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_FIRE) return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            onFired(id)
        }
    }

    @Synchronized
    private fun onFired(id: String) {
        val list = schedules
        // A leftover alarm from a previous process instance whose entry was deleted meanwhile: no-op
        // rather than guess. Same for an entry the user disabled without a resync reaching it.
        val schedule = list.firstOrNull { it.id == id && it.enabled } ?: return

        WeLogger.i(TAG, "schedule $id fired: ${schedule.action}")
        apply(schedule.action)

        if (schedule.kind == HideScheduleKind.ONCE) {
            removeStored(setOf(id))
            armedIds -= id
            resync()
            return
        }

        // REPEATING: re-arm just this entry for its next occurrence.
        val next = nextFireAfter(schedule, System.currentTimeMillis())
        if (next != null) arm(id, next) else armedIds -= id
    }

    private fun apply(action: HideScheduleAction) =
        HideContacts.setTemporarilyShown(action == HideScheduleAction.SHOW)

    // ── startup catch-up ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the action of the most recent fire time that already passed, so a schedule that came
     * due while the process was dead still takes effect. Elapsed [HideScheduleKind.ONCE] entries are
     * consumed here, matching their fire-once-then-delete contract.
     *
     * Runs at process attach: no Toast, no Activity, no UI — only the in-memory flag plus the
     * conversation-list refresh.
     *
     * Accepted limitation: when two enabled entries share the *same* most-recent past instant,
     * `maxByOrNull` keeps the first maximum — i.e. the tie is broken by list order, which is
     * insertion order, which the user never sees. `HideContactsScheduleUi.collidesWith` now refuses
     * to create such a pair, so this is unreachable for anything authored after that gate; entries
     * written to storage before it (or by hand) can still hit it. The outcome is deterministic per
     * stored list, just arbitrary — and one 显示/隐藏 flip either way, correctable by deleting one of
     * the two rows.
     */
    private fun catchUp() {
        val now = System.currentTimeMillis()
        val list = schedules
        val enabled = list.filter { it.enabled }

        val mostRecent = enabled
            .mapNotNull { schedule -> lastFireBefore(schedule, now)?.let { it to schedule } }
            .maxByOrNull { it.first }

        // Consume elapsed one-shots (including the winner, if it was one) before applying, so a crash
        // in the apply path can't leave a ONCE entry to fire again on the next launch. Removed by id
        // (see removeStored) rather than by writing back `list` minus the elapsed ones, so any entry
        // the `schedules` getter already rejected as malformed isn't silently deleted along with them.
        val elapsedOnceIds = list.filter {
            it.enabled && it.kind == HideScheduleKind.ONCE && it.atEpochMillis <= now
        }.mapTo(mutableSetOf()) { it.id }
        removeStored(elapsedOnceIds)

        if (mostRecent == null) return
        WeLogger.i(TAG, "startup catch-up applying ${mostRecent.second.action} from ${mostRecent.second.id}")
        apply(mostRecent.second.action)
    }

    // ── time arithmetic ──────────────────────────────────────────────────────────────────────────
    //
    // java.util.Calendar per repo convention (see TriggerScheduler.nextFire's DAILY branch, which is
    // the same "minuteOfDay -> epoch millis" computation without the day-of-week filter).

    /** The next fire time strictly after [now], or null if the entry can never fire again. */
    private fun nextFireAfter(schedule: HideSchedule, now: Long): Long? = when (schedule.kind) {
        HideScheduleKind.ONCE -> schedule.atEpochMillis.takeIf { it > now }

        HideScheduleKind.REPEATING -> {
            if (schedule.daysOfWeek.isEmpty()) null else {
                // 0..7 rather than 0..6: not DST — a sparse daysOfWeek. E.g. {SUNDAY} with
                // minuteOfDay=600 evaluated at 11:00 on a Sunday has already missed today's occurrence,
                // so the next one is 7 days out; offset 0..6 alone would miss it. Do not "optimize" this
                // to 0..6 — that breaks every single-day schedule.
                (0..7).firstNotNullOfOrNull { offset ->
                    val cal = dayAt(now, schedule.minuteOfDay, offset)
                    cal.takeIf {
                        it.timeInMillis > now && it.get(Calendar.DAY_OF_WEEK) in schedule.daysOfWeek
                    }?.timeInMillis
                }
            }
        }
    }

    /** The most recent fire time at or before [now], or null if the entry never fired. */
    private fun lastFireBefore(schedule: HideSchedule, now: Long): Long? = when (schedule.kind) {
        HideScheduleKind.ONCE -> schedule.atEpochMillis.takeIf { it in 1..now }

        HideScheduleKind.REPEATING -> {
            if (schedule.daysOfWeek.isEmpty()) null else {
                // The brief's 7-day look-back window: any enabled repeating entry with a non-empty
                // day set necessarily has an occurrence within it.
                (0..7).firstNotNullOfOrNull { offset ->
                    val cal = dayAt(now, schedule.minuteOfDay, -offset)
                    cal.takeIf {
                        it.timeInMillis <= now && it.get(Calendar.DAY_OF_WEEK) in schedule.daysOfWeek
                    }?.timeInMillis
                }
            }
        }
    }

    /** [now] shifted by [dayOffset] days, with the time-of-day pinned to [minuteOfDay]. */
    private fun dayAt(now: Long, minuteOfDay: Int, dayOffset: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (dayOffset != 0) add(Calendar.DAY_OF_MONTH, dayOffset)
        }
}
