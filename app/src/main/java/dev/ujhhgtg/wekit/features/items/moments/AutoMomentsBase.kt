package dev.ujhhgtg.wekit.features.items.moments

import android.view.View
import com.tencent.mm.plugin.sns.storage.SnsInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Shared base for [AutoLikeMoments] and [AutoRepostMoments].
 *
 * Owns timeline item lifecycle hooks and the per-item worker.
 */
abstract class AutoMomentsBase : ClickableFeature() {
    @Suppress("PropertyName") protected abstract val TAG: String

    protected class AutomationRun(val enabledAtMillis: Long) {
        val accountWindows = ConcurrentHashMap<String, Long>()
    }

    protected class AutomationScope(
        val run: AutomationRun,
        val account: String,
        val publishedAfterSeconds: Long,
    )

    @Volatile private var automationRun: AutomationRun? = null

    private val enabledAtKey
        get() = "${technicalId}_new_posts_enabled_at"

    protected fun startAutomation() {
        val enabledAt =
            KvStore.getLongOrDef(enabledAtKey, 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis().also { KvStore.putLong(enabledAtKey, it) }
        automationRun = AutomationRun(enabledAt)
    }

    protected fun stopAutomation() {
        automationRun = null
        KvStore.remove(enabledAtKey)
    }

    protected fun captureAutomationScope(): AutomationScope? {
        val run = automationRun ?: return null
        val account = WeApi.selfWxId
        if (account.isEmpty()) return null
        val afterMillis =
            run.accountWindows.computeIfAbsent(account) {
                val key = "${technicalId}_new_posts_after_$account"
                val previous = KvStore.getLongOrDef(key, 0L)
                if (previous >= run.enabledAtMillis) {
                    previous
                } else {
                    // A new account or a fresh enable must never inherit another account's backlog.
                    System.currentTimeMillis().also { KvStore.putLong(key, it) }
                }
            }
        return AutomationScope(run, account, afterMillis / 1000L)
    }

    protected fun isAutomationCurrent(scope: AutomationScope): Boolean =
        automationRun === scope.run && WeApi.selfWxId == scope.account

    protected fun matchesPublicationWindow(
        snsInfo: SnsInfo,
        mode: MomentAutomationMode,
        scope: AutomationScope,
    ): Boolean =
        mode == MomentAutomationMode.WHEN_SEEN ||
            snsInfo.field_createTime > scope.publishedAfterSeconds

    // ==================== Timeline hooks ====================

    protected fun installTimelineHooks() {
        WeMomentsApi.methodTimelineItemAttached.hookAfter {
            processTimelineHolder(args[0]!!, false)
        }
        WeMomentsApi.methodTimelineItemBound.hookAfter {
            processTimelineHolder(args[0]!!, true)
        }
        WeMomentsApi.methodTimelineItemUpdated.hookAfter {
            processTimelineHolder(args[0]!!, true)
        }
    }

    private fun processTimelineHolder(holder: Any, requireAttached: Boolean) {
        if (automationRun == null) return
        val itemView = holder.reflekt().getField("itemView", true) as View
        if (requireAttached && !itemView.isAttachedToWindow) return
        val snsInfo = WeMomentsApi.getTimelineSnsInfo(holder) ?: return
        processVisibleSnsInfo(snsInfo)
    }

    protected abstract fun processVisibleSnsInfo(snsInfo: SnsInfo)

    // ==================== Per-item worker ====================

    /**
     * Single worker for per-item processing.
     *
     * Serializes delayed actions without dropping newly visible items.
     */
    private val itemWorker: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
                1,
                1,
                WORKER_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
            ) { runnable ->
                Thread(runnable, "${TAG}Worker").apply { isDaemon = true }
            }
            .apply { allowCoreThreadTimeOut(true) }
    }

    /**
     * Runs [block] on the shared worker. [block] must guard itself — it runs off the main thread
     * where an escaping exception would reach `JavaCrashHandler` and take WeChat down with it.
     */
    protected fun submitItemWork(block: () -> Unit) {
        itemWorker.execute { block() }
    }

    // ==================== AntiMomentsDelete interception check ====================

    protected fun isIntercepted(snsInfo: SnsInfo): Boolean {
        val content = WeMomentsApi.getTimelineProto(snsInfo)?.contentDesc ?: return false
        return content.contains(AntiMomentsDelete.INTERCEPT_MARKER)
    }

    private companion object {
        const val WORKER_KEEP_ALIVE_SECONDS = 30L
    }
}
