package dev.ujhhgtg.wekit.pet.core

import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.engine.AgentEvent

/**
 * Agent event projection — pure. Maps the WeKit agent event vocabulary onto
 * the pet's visual phases and carries an optional completed-turn reward for
 * the ledger. Holds no persistent state of its own; callers keep one
 * [PetProjectionRuntime] per session and feed events in arrival order.
 */

/** Per-session facts needed to project the event stream. */
class PetProjectionRuntime {
    val activeTools = mutableSetOf<String>()
    var stepHadFailure = false

    /** Project one agent event into a pet activity transition. */
    fun project(event: AgentEvent): PetActivityTransition? = when (event) {
        is AgentEvent.RequestStarted -> {
            activeTools.clear()
            stepHadFailure = false
            PetActivityTransition(PetStateInput(ActivityPhase.WAITING, "准备开始"))
        }

        is AgentEvent.ReasoningDelta ->
            PetActivityTransition(PetStateInput(ActivityPhase.THINKING, "正在思考"))

        is AgentEvent.TextDelta ->
            PetActivityTransition(PetStateInput(ActivityPhase.REVIEW, "整理回复中"))

        is AgentEvent.ToolCallStarted -> {
            activeTools.add(event.callId)
            PetActivityTransition(PetStateInput(ActivityPhase.TOOL, "正在使用 ${displayToolName(event.toolName)}"))
        }

        is AgentEvent.ToolAwaitingApproval ->
            PetActivityTransition(PetStateInput(ActivityPhase.TOOL, "等待确认 ${displayToolName(event.toolName)}"))

        is AgentEvent.ToolCallFinished -> {
            activeTools.remove(event.callId)
            stepHadFailure = stepHadFailure ||
                event.status == ApprovalStatus.USER_REJECTED ||
                event.status == ApprovalStatus.AI_REJECTED
            if (activeTools.isNotEmpty()) {
                PetActivityTransition(PetStateInput(ActivityPhase.TOOL, "还有 ${activeTools.size} 个工具运行中"))
            } else if (stepHadFailure) {
                PetActivityTransition(PetStateInput(ActivityPhase.FAILED, "工具执行失败"))
            } else {
                PetActivityTransition(PetStateInput(ActivityPhase.THINKING, "处理工具结果"))
            }
        }

        is AgentEvent.TurnCompleted -> {
            activeTools.clear()
            PetActivityTransition(PetStateInput(ActivityPhase.DONE, "完成啦"), completedTurn = true)
        }

        is AgentEvent.TurnFailed -> {
            activeTools.clear()
            PetActivityTransition(PetStateInput(ActivityPhase.FAILED, "执行失败"))
        }

        is AgentEvent.UsageUpdated -> null
    }
}

/** One projected activity update, optionally carrying a completed turn reward. */
data class PetActivityTransition(
    val input: PetStateInput,
    val completedTurn: Boolean = false,
)

/** Keep tool names readable inside the compact status bubble. */
private fun displayToolName(name: String): String {
    val compact = name.replace(Regex("\\s+"), " ").trim().ifEmpty { "工具" }
    return if (compact.length <= 24) compact else compact.take(21) + "..."
}
