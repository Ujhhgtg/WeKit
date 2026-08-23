package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.model.LlmClient
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRequest
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.agent.tool.ToolPermissionSource
import dev.ujhhgtg.wekit.agent.tool.ToolProvider
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class ToolCallExecutorTest {
    @Test
    fun `enabled call reports automatic approval`() = runBlocking {
        val registry = ToolRegistry(
            ToolPermissionSource { _, _, factory -> factory },
            listOf(provider(ToolMode.ENABLED) { "pong" }),
        )

        val result = ToolCallExecutor(registry, ApprovalGateway(ManualApprovalHandler { error("unexpected") }, null))
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AUTO_ALLOWED, result.status)
        assertEquals("pong", result.text)
    }

    @Test
    fun `smart-approved call reports AI approval`() = runBlocking {
        val registry = ToolRegistry(
            ToolPermissionSource { _, _, factory -> factory },
            listOf(provider(ToolMode.SMART_APPROVAL) { "pong" }),
        )
        val client = object : LlmClient {
            override fun stream(request: LlmRequest) = flowOf(
                LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "{\"allow\":true}"), "stop"),
            )
        }
        val gateway = ApprovalGateway(
            ManualApprovalHandler { error("unexpected") },
            SmallModelRef(client, "reviewer", null),
        )

        val result = ToolCallExecutor(registry, gateway)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AI_APPROVED, result.status)
        assertEquals("pong", result.text)
    }

    @Test
    fun `smart-rejected call reports AI rejection`() = runBlocking {
        val registry = ToolRegistry(
            ToolPermissionSource { _, _, factory -> factory },
            listOf(provider(ToolMode.SMART_APPROVAL) { error("must not execute") }),
        )
        val gateway = ApprovalGateway(ManualApprovalHandler { error("unexpected") }, null)

        val result = ToolCallExecutor(registry, gateway)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AI_REJECTED, result.status)
    }

    @Test
    fun `approved call executes once and preserves approval status`() = runBlocking {
        var executions = 0
        val provider = object : ToolProvider {
            override val id = "test"
            override val name = "Test"
            override val kind = ProviderKind.MCP
            override val isAvailable = true
            override fun listTools() = listOf(ProviderTool("ping", "ping", JsonObject(emptyMap()), ToolMode.MANUAL_APPROVAL))
            override suspend fun execute(toolName: String, arguments: JsonObject): String { executions++; return "pong" }
        }
        val registry = ToolRegistry(ToolPermissionSource { _, _, factory -> factory }, listOf(provider))
        val gateway = ApprovalGateway(ManualApprovalHandler { ManualApprovalResult.Approved }, null)
        val result = ToolCallExecutor(registry, gateway).execute(
            LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context(),
        )
        assertEquals("pong", result.text)
        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_APPROVED, result.status)
        assertEquals(1, executions)
    }

    @Test
    fun `denied manual call does not execute and reports user rejection`() = runBlocking {
        var executions = 0
        val provider = provider(ToolMode.MANUAL_APPROVAL) { executions++; "unexpected" }
        val registry = ToolRegistry(ToolPermissionSource { _, _, factory -> factory }, listOf(provider))
        val gateway = ApprovalGateway(
            ManualApprovalHandler { ManualApprovalResult.Rejected("no") }, null,
        )

        val result = ToolCallExecutor(registry, gateway).execute(
            LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context(),
        )

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_REJECTED, result.status)
        assertTrue(result.text.contains("no"))
        assertEquals(0, executions)
    }

    private fun provider(mode: ToolMode, execute: suspend () -> String) = object : ToolProvider {
        override val id = "test"
        override val name = "Test"
        override val kind = ProviderKind.MCP
        override val isAvailable = true
        override fun listTools() = listOf(ProviderTool("ping", "ping", JsonObject(emptyMap()), mode))
        override suspend fun execute(toolName: String, arguments: JsonObject) = execute()
    }
}
