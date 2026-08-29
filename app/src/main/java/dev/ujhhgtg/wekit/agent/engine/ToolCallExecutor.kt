package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.model.LlmJson
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.tool.PermissionLevel
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ToolExecutionContext(val callId: String) : AbstractCoroutineContextElement(ToolExecutionContext) {
    companion object Key : CoroutineContext.Key<ToolExecutionContext>
}

class ToolCallExecutor(
    private val registry: ToolRegistry,
    private val approvalGateway: ApprovalGateway,
    /**
     * Resolves the permission level at CALL time, not at executor construction: the requirement is
     * that a level change takes effect immediately, including on tool calls later in a turn that is
     * already running.
     */
    private val permissionLevel: suspend () -> PermissionLevel,
) {
    data class Context(
        val modelExplanation: String? = null,
        val visibility: ToolVisibility = ToolVisibility.fromGlobals(),
        val origin: ToolCallOrigin = ToolCallOrigin.DIRECT,
        val onAwaitingApproval: suspend (String) -> Unit = {},
    )

    data class Result(
        val text: String,
        val status: ApprovalStatus,
        val providerId: String,
        val executionSucceeded: Boolean = true,
    )

    suspend fun execute(call: LlmToolCall, context: Context): Result {
        val args = runCatching { LlmJson.json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        val tool = registry.findByExposedName(call.name, context.visibility)
            ?: return Result("Unknown tool: ${call.name}", ApprovalStatus.AUTO_ALLOWED, "", false)
        if (!ToolRegistry.isCallAllowed(tool.provider.kind, tool.exposedName, tool.bareName, context.origin)) {
            return Result("Tool is not available through the environment bridge: ${tool.exposedName}", ApprovalStatus.AUTO_ALLOWED, tool.provider.id, false)
        }
        val level = permissionLevel()
        val behavior = behaviorFor(level, tool.sideEffect, tool.provider.kind, tool.bareName)
        if (behavior == ApprovalBehavior.MANUAL) context.onAwaitingApproval(call.name)
        return when (val decision = approvalGateway.decide(
            behavior, tool.exposedName, tool.provider.name, call.argumentsJson, context.modelExplanation,
        )) {
            is ApprovalDecision.Allowed -> {
                val status = when (behavior) {
                    ApprovalBehavior.MANUAL -> ApprovalStatus.USER_APPROVED
                    ApprovalBehavior.SMART -> ApprovalStatus.AI_APPROVED
                    ApprovalBehavior.AUTO -> ApprovalStatus.AUTO_ALLOWED
                }
                val execution = runCatching {
                    withContext(ToolExecutionContext(call.id)) { registry.execute(tool, args) }
                }
                Result(
                    execution.getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" },
                    status,
                    tool.provider.id,
                    execution.isSuccess,
                )
            }
            is ApprovalDecision.Denied -> Result(
                approvalGateway.deniedResultText(decision),
                if (decision.bySmartReview) ApprovalStatus.AI_REJECTED else ApprovalStatus.USER_REJECTED,
                tool.provider.id,
                false,
            )
        }
    }
}
