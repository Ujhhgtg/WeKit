package dev.ujhhgtg.wekit.workflow.trigger

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Top-level coordinator for the Workflow trigger runtime.
 *
 * - [WorkflowTriggerBus] handles WeChat DB events (NewMessage, NewMoment).
 * - [WorkflowScheduler] manages timer-based (Schedule) workflows.
 *
 * Call [start] once during module initialisation (e.g. from
 * [dev.ujhhgtg.wekit.features.api.agent.WeAgentService.init] or from the
 * WeKit startup hook). Idempotent: calling [start] multiple times is safe.
 */
object WorkflowRuntime {

    private const val TAG = "WorkflowRuntime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    private lateinit var triggerBus: WorkflowTriggerBus
    private lateinit var scheduler: WorkflowScheduler

    fun start() {
        if (started) return
        started = true

        triggerBus = WorkflowTriggerBus(scope)
        WeDatabaseListenerApi.addListener(triggerBus)

        scheduler = WorkflowScheduler(scope)
        scheduler.start()

        WeLogger.i(TAG, "WorkflowRuntime started")
    }
}
