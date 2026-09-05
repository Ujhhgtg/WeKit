package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap
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

    private val settings = MomentsAutomationSettings.Like
    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

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
        thread(name = "ScanMomentsToAutoLikeThread") {
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
        if (isIntercepted(snsInfo) || snsTableId in handledSnsIds) return

        val action = rules.effectiveAction
        val liked = WeMomentsApi.isLiked(snsInfo)
        if (action == MomentAutomationAction.LIKE && liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (action == MomentAutomationAction.UNLIKE && !liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

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

                snsTableId in handledSnsIds ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already handled")

                !latestRules.matchesMoment(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "rules changed")

                WeMomentsApi.isDeleted(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "deleted/recalled")

                latestRules.effectiveAction == MomentAutomationAction.LIKE && WeMomentsApi.isLiked(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already liked")

                latestRules.effectiveAction == MomentAutomationAction.UNLIKE && !WeMomentsApi.isLiked(snsInfo) ->
                    WeMomentsApi.ActionResult(success = true, sent = false, message = "already unliked")

                latestRules.effectiveAction == MomentAutomationAction.UNLIKE -> WeMomentsApi.unlike(snsInfo)
                else -> WeMomentsApi.like(snsInfo)
            }
            if (outcome.success && isAutomationCurrent(scope)) handledSnsIds.add(snsTableId)
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
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
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
        if (delay > 0L) {
            val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
            if (wait > 0L) Thread.sleep(wait)
        }
        val result = block()
        if (result.sent) lastActionSentAt = System.currentTimeMillis()
        result
    }

    private fun actionLabel(action: MomentAutomationAction): String =
        if (action == MomentAutomationAction.UNLIKE) "unlike" else "like"
}
