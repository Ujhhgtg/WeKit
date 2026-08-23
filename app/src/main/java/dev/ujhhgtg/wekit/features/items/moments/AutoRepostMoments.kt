package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object AutoRepostMoments : AutoMomentsBase(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    AutoRefresh.IRefreshListener {

    override val technicalId = "自动转发"
    override val nameRes = R.string.feature_auto_repost_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_auto_repost_moments_description

    override val TAG = "AutoRepostMoments"

    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_FORWARDED_RECORDS = 1000

    private val settings = MomentsAutomationSettings.Repost
    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

    private var forwardedSnsIds by WePrefs.prefOption(
        "moments_auto_forward_forwarded_ids",
        emptySet()
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        AutoRefresh.addListener(this)
        installTimelineHooks()
        if (settings.hasAllLoadedTargets()) scanCachedTargetMoments()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        AutoRefresh.removeListener(this)
    }

    override fun onRefresh() {
        if (settings.hasAllLoadedTargets()) scanCachedTargetMoments()
    }

    override fun onClick(context: ComponentActivity) {
        settings.showMainDialog(context) {
            handledSnsIds.clear()
            lastAttemptAt.clear()
            if (settings.hasAllLoadedTargets()) scanCachedTargetMoments()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        processSnsInfoValues(table, values)
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        processSnsInfoValues(table, values)
    }

    override fun processVisibleItems(list: ViewGroup) {
        for (index in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(index))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        val owner = values.getAsString("userName")?.trim().orEmpty()
        val rules = settings.resolve(owner)
        if (!rules.process.enabled || rules.effectiveMode != MomentAutomationMode.ALL_LOADED) return

        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments() {
        if (!settings.hasAllLoadedTargets()) return
        thread(name = "ScanMomentsToAutoForwardThread") {
            val snsIds = runCatching { queryCachedTargetSnsIds() }
                .onFailure { WeLogger.w(TAG, "failed to query cached target moments", it) }
                .getOrDefault(emptyList())

            WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
            for (snsId in snsIds) {
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: continue
                runCatching { processSnsInfo(snsInfo, "cached") }
                    .onFailure { WeLogger.w(TAG, "auto-forward processing failed", it) }
            }
        }
    }

    private fun queryCachedTargetSnsIds(): List<Long> {
        if (!WeDatabaseApi.isReady) return emptyList()
        val sql = """
            SELECT snsId, userName
            FROM SnsInfo
            WHERE snsId != 0
              AND sourceType = 0
            ORDER BY createTime DESC
        """.trimIndent()

        val result = mutableListOf<Long>()
        WeDatabaseApi.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(0)
                val owner = cursor.getString(1).orEmpty()
                val rules = settings.resolve(owner)
                if (!rules.process.enabled || rules.effectiveMode != MomentAutomationMode.ALL_LOADED) continue
                result += snsId
            }
        }
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        val rules = settings.resolve(owner)
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (source != "visible" && rules.effectiveMode != MomentAutomationMode.ALL_LOADED) return
        if (!rules.matchesMoment(snsInfo)) return
        if (WeMomentsApi.isDeleted(snsInfo)) return

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        if (isIntercepted(snsInfo) || snsTableId in handledSnsIds) return
        if (isAlreadyForwarded(snsTableId)) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

        val result = sendWithDelay(
            delay = rules.interval.value(),
            // Committed while the action lock is still held: a repost whose media download outlives
            // RETRY_INTERVAL_MS (routine for videos/live photos) lets a second thread clear
            // canAttempt and queue up on the lock, and if the mark landed after the lock was
            // released that thread would see isAlreadyForwarded == false and post a duplicate.
            commit = { committed ->
                if (committed.success) {
                    handledSnsIds.add(snsTableId)
                    if (committed.sent) markForwarded(snsTableId)
                }
            }
        ) {
            val latestOwner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
            val latestRules = settings.resolve(latestOwner)
            when {
                latestOwner.isBlank() || latestOwner == WeApi.selfWxId ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "target skipped")

                source != "visible" && latestRules.effectiveMode != MomentAutomationMode.ALL_LOADED ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "mode changed")

                !latestRules.matchesMoment(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "rules changed")

                WeMomentsApi.isDeleted(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "deleted/recalled")

                isAlreadyForwarded(snsTableId) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already forwarded")

                else -> runBlocking { WeMomentsApi.quickRepostEnsuringCached(snsInfo) }
            }
        }

        if (result.success) {
            WeLogger.i(TAG, "auto-forward $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-forward $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        submitItemWork {
            runCatching { processSnsInfo(snsInfo, source) }
                .onFailure { WeLogger.w(TAG, "auto-forward processing failed", it) }
        }
    }

    /**
     * Runs [block] under the action lock, honouring the configured 操作间隔, then applies [commit]
     * to the outcome *before* the lock is released so no other thread can observe a published
     * repost as not-yet-forwarded.
     */
    private fun sendWithDelay(
        delay: Long,
        commit: (WeMomentsApi.ActionResult) -> Unit,
        block: () -> WeMomentsApi.ActionResult
    ): WeMomentsApi.ActionResult = synchronized(actionLock) {
        if (delay > 0L) {
            val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
            if (wait > 0L) Thread.sleep(wait)
        }
        val result = block()
        if (result.sent) lastActionSentAt = System.currentTimeMillis()
        commit(result)
        result
    }

    private fun isAlreadyForwarded(snsTableId: String): Boolean = snsTableId in forwardedSnsIds

    @Synchronized
    private fun markForwarded(snsTableId: String) {
        val updated = LinkedHashSet(forwardedSnsIds)
        updated.add(snsTableId)
        if (updated.size > MAX_FORWARDED_RECORDS) {
            val iterator = updated.iterator()
            repeat(updated.size - MAX_FORWARDED_RECORDS) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        forwardedSnsIds = updated
    }
}
