package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.model.LlmJson
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolCallExecutor(
    private val registry: ToolRegistry,
    private val approvalGateway: ApprovalGateway,
) {
    data class Context(
        val modelExplanation: String? = null,
        val visibility: ToolVisibility = ToolVisibility.fromGlobals(),
        val origin: ToolCallOrigin = ToolCallOrigin.DIRECT,
        val onAwaitingApproval: suspend (String) -> Unit = {},
    )

    data class Result(val text: String, val status: ApprovalStatus, val providerId: String)

    suspend fun execute(call: LlmToolCall, context: Context): Result {
        val args = runCatching { LlmJson.json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        val tool = registry.findByExposedName(call.name, context.visibility)
            ?: return Result("Unknown tool: ${call.name}", ApprovalStatus.AUTO_ALLOWED, "")
        if (!ToolRegistry.isCallAllowed(tool.exposedName, context.origin)) {
            return Result("Tool is not available through the environment bridge: ${tool.exposedName}", ApprovalStatus.AUTO_ALLOWED, tool.provider.id)
        }
        if (tool.mode == ToolMode.MANUAL_APPROVAL) context.onAwaitingApproval(call.name)
        return when (val decision = approvalGateway.decide(
            tool.mode, tool.exposedName, tool.provider.name, call.argumentsJson, context.modelExplanation,
        )) {
            is ApprovalDecision.Allowed -> {
                val status = when (tool.mode) {
                    ToolMode.MANUAL_APPROVAL -> ApprovalStatus.USER_APPROVED
                    ToolMode.SMART_APPROVAL -> ApprovalStatus.AI_APPROVED
                    else -> ApprovalStatus.AUTO_ALLOWED
                }
                val text = runCatching { registry.execute(tool, args) }
                    .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
                Result(text, status, tool.provider.id)
            }
            is ApprovalDecision.Denied -> Result(
                approvalGateway.deniedResultText(decision),
                if (decision.bySmartReview) ApprovalStatus.AI_REJECTED else ApprovalStatus.USER_REJECTED,
                tool.provider.id,
            )
        }
    }
}
