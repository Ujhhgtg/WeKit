package dev.ujhhgtg.wekit.workflow.trigger

import android.content.ContentValues
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.engine.TriggerContext
import dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine
import dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Listens to WeChat's SQLite event stream and fires any enabled
 * [WorkflowTrigger.NewMessage] / [WorkflowTrigger.NewMoment] workflows
 * whose conditions match the incoming row.
 *
 * Registered by [WorkflowRuntime] when the module starts.
 */
class WorkflowTriggerBus(private val scope: CoroutineScope) :
    WeDatabaseListenerApi.IInsertListener {

    private val TAG = "WorkflowTriggerBus"

    override fun onInsert(table: String, values: ContentValues) {
        when (table) {
            "message" -> handleMessage(values)
            "SnsInfo" -> handleMoment(values)
        }
    }

    // ── Message trigger ───────────────────────────────────────────────────────

    private fun handleMessage(values: ContentValues) {
        val isSend = (values.getAsInteger("isSend") ?: 0) != 0
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: ""
        val msgType = values.getAsInteger("type") ?: 0
        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        val sender = if (isSend) "" else values.getAsString("sender") ?: talker

        scope.launch {
            val workflows = WorkflowRepository.getEnabledForTrigger("NewMessage")
            for (wf in workflows) {
                val trig = wf.trigger as? WorkflowTrigger.NewMessage ?: continue
                if (!matchesMessage(trig, talker, content, isSend)) continue

                val ctx = TriggerContext.newMessage(
                    sender = sender.ifEmpty { talker },
                    talker = talker,
                    content = content,
                    msgType = msgType,
                    msgSvrId = msgSvrId,
                )
                WeLogger.d(TAG, "firing NewMessage workflow '${wf.name}' (talker=$talker)")
                WorkflowEngine.execute(wf, ctx)
            }
        }
    }

    private fun matchesMessage(
        trig: WorkflowTrigger.NewMessage,
        talker: String,
        content: String,
        isSend: Boolean,
    ): Boolean {
        val directionOk = when (trig.direction) {
            dev.ujhhgtg.wekit.workflow.model.MessageDirection.RECEIVED -> !isSend
            dev.ujhhgtg.wekit.workflow.model.MessageDirection.SENT -> isSend
            dev.ujhhgtg.wekit.workflow.model.MessageDirection.BOTH -> true
        }
        if (!directionOk) return false
        if (trig.talkerRegex != null && !talker.contains(Regex(trig.talkerRegex))) return false
        if (trig.contentRegex != null && !content.contains(Regex(trig.contentRegex))) return false
        return true
    }

    // ── Moment trigger ────────────────────────────────────────────────────────

    private fun handleMoment(values: ContentValues) {
        val author = values.getAsString("userName")?.trim() ?: return
        val content = values.getAsString("content") ?: ""
        val snsId = values.getAsLong("snsId")?.toString() ?: ""
        val type = values.getAsInteger("type") ?: 0
        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return   // skip deletions

        scope.launch {
            val workflows = WorkflowRepository.getEnabledForTrigger("NewMoment")
            for (wf in workflows) {
                val trig = wf.trigger as? WorkflowTrigger.NewMoment ?: continue
                if (trig.senderWxids != null && author !in trig.senderWxids) continue

                val ctx = TriggerContext.newMoment(
                    sender = author,
                    content = content,
                    snsId = snsId,
                    type = type,
                )
                WeLogger.d(TAG, "firing NewMoment workflow '${wf.name}' (author=$author)")
                WorkflowEngine.execute(wf, ctx)
            }
        }
    }
}
