package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi.classImproveInteractionLayout
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi.classImproveSnsInfo
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi.fieldInteractionSnsInfo
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Shared base for [AutoLikeMoments] and [AutoRepostMoments].
 *
 * Owns the view-tree wiring (timeline hooks, attach/scroll listeners) and the
 * SnsInfo location logic so neither subclass has to duplicate it.
 */
abstract class AutoMomentsBase : ClickableFeature() {

    @Suppress("PropertyName")
    protected abstract val TAG: String

    protected class AutomationRun(val enabledAtMillis: Long) {
        val accountWindows = ConcurrentHashMap<String, Long>()
    }

    protected class AutomationScope(
        val run: AutomationRun,
        val account: String,
        val publishedAfterSeconds: Long
    )

    @Volatile
    private var automationRun: AutomationRun? = null

    private val enabledAtKey get() = "${technicalId}_new_posts_enabled_at"

    protected fun startAutomation() {
        val enabledAt = WePrefs.getLongOrDef(enabledAtKey, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis().also { WePrefs.putLong(enabledAtKey, it) }
        automationRun = AutomationRun(enabledAt)
    }

    protected fun stopAutomation() {
        automationRun = null
        WePrefs.remove(enabledAtKey)
        // Old view callbacks may survive unhooking, but cannot submit work while stopped.
        timelineHooksInstalled = false
        synchronized(attachedRoots) { attachedRoots.clear() }
    }

    protected fun captureAutomationScope(): AutomationScope? {
        val run = automationRun ?: return null
        val account = WeApi.selfWxId
        if (account.isEmpty()) return null
        val afterMillis = run.accountWindows.computeIfAbsent(account) {
            val key = "${technicalId}_new_posts_after_$account"
            val previous = WePrefs.getLongOrDef(key, 0L)
            if (previous >= run.enabledAtMillis) previous else {
                // A new account or a fresh enable must never inherit another account's backlog.
                System.currentTimeMillis().also { WePrefs.putLong(key, it) }
            }
        }
        return AutomationScope(run, account, afterMillis / 1000L)
    }

    protected fun isAutomationCurrent(scope: AutomationScope): Boolean =
        automationRun === scope.run && WeApi.selfWxId == scope.account

    protected fun matchesPublicationWindow(
        snsInfo: Any,
        mode: MomentAutomationMode,
        scope: AutomationScope
    ): Boolean = mode == MomentAutomationMode.WHEN_SEEN ||
            WeMomentsApi.getCreateTimeSeconds(snsInfo) > scope.publishedAfterSeconds

    protected val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var timelineHooksInstalled = false

    // ==================== Timeline hooks ====================

    protected fun installTimelineHooks() {
        if (timelineHooksInstalled) return
        timelineHooksInstalled = true
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz ->
            clazz.reflekt()
                .firstMethod { name = "onCreate" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
            clazz.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            root.postDelayed({
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "failed to attach Moments list observer", it) }
            }, delayMs.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        if (automationRun == null) return
        val list = root.findViewWhich { it is WxRecyclerView } as? WxRecyclerView? ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }

        // A RecyclerView.OnScrollListener would be the natural fit, but WeKit deliberately never
        // links against androidx.recyclerview (the host owns that class; our WxRecyclerView stub is
        // a plain ViewGroup), so use the framework-level equivalent: RecyclerView.dispatchOnScrolled
        // calls View.onScrollChanged, which makes the ViewTreeObserver fire this listener once per
        // frame in which the list actually scrolled. That replaces the previous pair of layout
        // listeners, which fired on every layout pass of every timeline item.
        val scan = Runnable { scanVisibleItems(list) }
        var lastScanAt = 0L

        list.viewTreeObserver.addOnScrollChangedListener {
            // Trailing scan: the throttle below can swallow the final scroll callback, and the
            // resting position is exactly the one the user cares about.
            list.removeCallbacks(scan)
            list.postDelayed(scan, SCAN_SETTLE_MS)

            val now = SystemClock.uptimeMillis()
            if (now - lastScanAt >= SCAN_THROTTLE_MS) {
                lastScanAt = now
                scan.run()
            }
        }

        scanVisibleItems(list)
    }

    /**
     * Main-thread entry point for [processVisibleItems]. View listeners are not covered by the hook
     * framework's safety net, so anything escaping here would crash WeChat outright.
     */
    private fun scanVisibleItems(list: ViewGroup) {
        if (automationRun == null) return
        runCatching { processVisibleItems(list) }
            .onFailure { WeLogger.w(TAG, "failed to scan visible Moments items", it) }
    }

    /** Called whenever visible list items may have changed. */
    protected abstract fun processVisibleItems(list: ViewGroup)

    // ==================== Per-item worker ====================

    /**
     * Single bounded worker for per-item processing.
     *
     * Every scan inspects each visible child, so a `thread {}` per item created hundreds of threads
     * per second while scrolling — and with a non-zero 操作间隔 they piled up blocked on the action
     * lock until `pthread_create` failed. One serialized thread with a small queue is self-limiting
     * instead: once the queue is full the surplus requests are simply dropped, and the next scan
     * picks those items up again anyway (the per-item dedup guards make re-submission cheap).
     */
    private val itemWorker: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1,
            1,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(WORKER_QUEUE_CAPACITY),
            { runnable -> Thread(runnable, "${TAG}Worker").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
    }

    /**
     * Runs [block] on the shared worker. [block] must guard itself — it runs off the main thread
     * where an escaping exception would reach `JavaCrashHandler` and take WeChat down with it.
     */
    protected fun submitItemWork(block: () -> Unit) {
        runCatching { itemWorker.execute(Runnable { block() }) }
            .onFailure { WeLogger.w(TAG, "failed to submit Moments item work", it) }
    }

    // ==================== SnsInfo location ====================

    protected fun locateSnsInfo(itemView: View): Any? {
        extractImproveSnsInfo(itemView)?.let { return it }

        val interactionView = itemView.findViewWhich {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return extractImproveSnsInfo(interactionView)
            ?: fieldInteractionSnsInfo.field.get(interactionView)
    }

    private fun extractImproveSnsInfo(receiver: Any): Any? {
        if (classImproveSnsInfo.clazz.isInstance(receiver)) return receiver

        receiver.reflekt()
            .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.invoke()?.let { return it }

        receiver.reflekt().firstMethodOrNull {
            name = "getImproveListItem"
            parameters()
            superclass()
        }?.invoke()?.let { listItem ->
            listItem.reflekt()
                .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.invoke()?.let { return it }
            listItem.reflekt()
                .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.get()?.let { return it }
        }

        return receiver.reflekt()
            .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.get()
    }

    // ==================== AntiMomentsDelete interception check ====================

    protected fun isIntercepted(snsInfo: Any): Boolean {
        val content = WeMomentsApi.getContentText(snsInfo) ?: return false
        return content.contains(AntiMomentsDelete.INTERCEPT_MARKER)
    }

    private companion object {
        /** Minimum gap between two scans triggered by a continuous scroll. */
        const val SCAN_THROTTLE_MS = 150L

        /** Delay after the last scroll callback before the settling scan runs. */
        const val SCAN_SETTLE_MS = 250L

        const val WORKER_QUEUE_CAPACITY = 64
        const val WORKER_KEEP_ALIVE_SECONDS = 30L
    }
}
