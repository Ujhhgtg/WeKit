package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android._mainHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object AutoLikeMoments : AutoMomentsBase(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    AutoRefresh.IRefreshListener {

    override val technicalId = "自动点赞"
    override val nameRes = R.string.feature_auto_like_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_auto_like_moments_description

    override val TAG = "AutoLikeMoments"

    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MIN_ACTION_INTERVAL_MS = 2_000L
    private const val MAIN_THREAD_TIMEOUT_SECONDS = 10L

    private val settings = MomentsAutomationSettings.Like
    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()
    private val scanningCachedMoments = AtomicBoolean()

    @Volatile
    private var lastActionAttemptAt = 0L

    override fun onEnable() {
        startAutomation()
        handledSnsIds.clear()
        lastAttemptAt.clear()
        WeDatabaseListenerApi.addListener(this)
        AutoRefresh.addListener(this)
        installTimelineHooks()
        if (settings.hasAllLoadedTargets()) scanCachedTargetMoments()
    }

    override fun onDisable() {
        stopAutomation()
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
        if (sourceType and WeMomentsApi.ACTIVE_SOURCE_MASK == 0 ||
            sourceType and WeMomentsApi.AD_SOURCE_FLAG != 0) return

        val liked = values.getAsInteger("likeFlag") ?: 0 != 0
        if (rules.effectiveAction == MomentAutomationAction.LIKE && liked) return
        if (rules.effectiveAction == MomentAutomationAction.UNLIKE && !liked) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments() {
        if (!settings.hasAllLoadedTargets()) return
        val scope = captureAutomationScope() ?: return
        if (!scanningCachedMoments.compareAndSet(false, true)) return
        thread(name = "ScanMomentsToAutoLikeThread") {
            try {
                if (!isAutomationCurrent(scope)) return@thread
                val snsIds = runCatching { queryCachedTargetSnsIds(scope) }
                    .onFailure { WeLogger.w(TAG, "failed to query cached target moments", it) }
                    .getOrDefault(emptyList())

                WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
                for (snsId in snsIds) {
                    if (!isAutomationCurrent(scope)) break
                    val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: continue
                    runCatching { processSnsInfo(snsInfo, "cached", scope) }
                        .onFailure { WeLogger.w(TAG, "auto-like processing failed", it) }
                }
            } finally {
                scanningCachedMoments.set(false)
            }
        }
    }

    private fun queryCachedTargetSnsIds(scope: AutomationScope): List<Long> {
        val sql = """
            SELECT snsId, userName, IFNULL(likeFlag, 0)
            FROM SnsInfo
            WHERE snsId != 0
              AND createTime > ?
              AND (sourceType & ${WeMomentsApi.ACTIVE_SOURCE_MASK}) != 0
              AND (sourceType & ${WeMomentsApi.AD_SOURCE_FLAG}) = 0
            ORDER BY createTime DESC
        """.trimIndent()

        val result = mutableListOf<Long>()
        WeMomentsApi.rawQuerySnsInfo(sql, arrayOf(scope.publishedAfterSeconds.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(0)
                val owner = cursor.getString(1).orEmpty()
                val liked = cursor.getInt(2) != 0
                val rules = settings.resolve(owner)
                if (!rules.process.enabled || rules.effectiveMode != MomentAutomationMode.ALL_LOADED) continue
                if (rules.effectiveAction == MomentAutomationAction.LIKE && liked) continue
                if (rules.effectiveAction == MomentAutomationAction.UNLIKE && !liked) continue
                result += snsId
            }
        }
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String, scope: AutomationScope) {
        if (!isAutomationCurrent(scope)) return
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        val rules = settings.resolve(owner)
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (source != "visible" && rules.effectiveMode != MomentAutomationMode.ALL_LOADED) return
        if (!matchesPublicationWindow(snsInfo, rules.effectiveMode, scope)) return
        if (!rules.matchesMoment(snsInfo)) return
        if (WeMomentsApi.isDeleted(snsInfo)) return

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        val action = rules.effectiveAction
        if (isIntercepted(snsInfo) || wasHandled(scope, snsTableId, action)) return

        val handledKey = "${scope.account}:$snsTableId:$action"
        val liked = WeMomentsApi.isLiked(snsInfo)
        if (action == MomentAutomationAction.LIKE && liked) {
            handledSnsIds.add(handledKey)
            return
        }
        if (action == MomentAutomationAction.UNLIKE && !liked) {
            handledSnsIds.add(handledKey)
            return
        }
        if (!canAttempt(handledKey)) return

        val result = sendWithDelay(rules.interval.value()) {
            if (!isAutomationCurrent(scope)) {
                return@sendWithDelay WeMomentsApi.ActionResult(success = true, sent = false, message = "automation stopped")
            }
            val latestOwner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
            val latestRules = settings.resolve(latestOwner)
            val outcome = when {
                latestOwner.isBlank() || latestOwner == WeApi.selfWxId ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "target skipped")

                source != "visible" && latestRules.effectiveMode != MomentAutomationMode.ALL_LOADED ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "mode changed")

                !matchesPublicationWindow(snsInfo, latestRules.effectiveMode, scope) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "predates activation")

                wasHandled(scope, snsTableId, latestRules.effectiveAction) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already handled")

                !latestRules.matchesMoment(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "rules changed")

                WeMomentsApi.isDeleted(snsInfo) || isIntercepted(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "deleted/recalled")

                latestRules.effectiveAction == MomentAutomationAction.LIKE && WeMomentsApi.isLiked(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already liked")

                latestRules.effectiveAction == MomentAutomationAction.UNLIKE && !WeMomentsApi.isLiked(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already unliked")

                latestRules.effectiveAction == MomentAutomationAction.UNLIKE -> WeMomentsApi.unlike(snsInfo)
                else -> WeMomentsApi.like(snsInfo)
            }
            // A queued request can still be rejected by the server. Remember dispatches rather
            // than relying on likeFlag, which remains clear after a failed request and on stale
            // SnsInfo instances. Do not replay those requests after a refresh or process restart.
            if (outcome.sent) rememberDispatch(scope, snsTableId, latestRules.effectiveAction)
            outcome
        }

        if (result.success) {
            WeLogger.i(TAG, "auto-${actionLabel(action)} $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-${actionLabel(action)} $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = SystemClock.elapsedRealtime()
        val last = lastAttemptAt[snsTableId]
        if (last != null && now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    private fun wasHandled(scope: AutomationScope, snsTableId: String, action: MomentAutomationAction): Boolean =
        "${scope.account}:$snsTableId:$action" in handledSnsIds ||
                "$snsTableId:$action" in WePrefs.getStringSetOrDef(historyKey(scope), emptySet())

    private fun historyKey(scope: AutomationScope): String = "${technicalId}_dispatched_${scope.account}"

    // Called only from the main-thread action below, so updates to an account's history are serial.
    private fun rememberDispatch(scope: AutomationScope, snsTableId: String, action: MomentAutomationAction) {
        val key = historyKey(scope)
        val history = WePrefs.getStringSetOrDef(key, emptySet()).toMutableSet()
        // Retain the last dispatched action so switching between like/unlike remains possible.
        MomentAutomationAction.entries.forEach {
            history.remove("$snsTableId:$it")
            handledSnsIds.remove("${scope.account}:$snsTableId:$it")
        }
        history.add("$snsTableId:$action")
        WePrefs.putStringSet(key, history)
        handledSnsIds.add("${scope.account}:$snsTableId:$action")
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        val scope = captureAutomationScope() ?: return
        submitItemWork {
            runCatching { processSnsInfo(snsInfo, source, scope) }
                .onFailure { WeLogger.w(TAG, "auto-like processing failed", it) }
        }
    }

    private fun sendWithDelay(
        delay: Long,
        block: () -> WeMomentsApi.ActionResult
    ): WeMomentsApi.ActionResult = synchronized(actionLock) {
        val wait = delay.coerceAtLeast(MIN_ACTION_INTERVAL_MS) -
                (SystemClock.elapsedRealtime() - lastActionAttemptAt)
        if (wait > 0L) Thread.sleep(wait)

        // SnsService mutates cached SNS protobufs. Match WeChat's UI-thread call path so these
        // writes cannot race SnsCommentDetailUI's size calculation / toByteArray serialization.
        // Keep the final scope/rule/dedup checks in the same main-thread action as the mutation.
        val task = FutureTask {
            block().also {
                if (it.sent || !it.success) lastActionAttemptAt = SystemClock.elapsedRealtime()
            }
        }
        check(_mainHandler.post(task)) { "failed to schedule Moments action" }
        try {
            task.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            // A timed-out queued action must not send later, after the caller has moved on.
            task.cancel(false)
            _mainHandler.removeCallbacks(task)
        }
    }

    private fun actionLabel(action: MomentAutomationAction): String =
        if (action == MomentAutomationAction.UNLIKE) "unlike" else "like"
}
