package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.model.LlmClient
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRequest
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.tool.PermissionLevel
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolProvider
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class ToolCallExecutorTest {
    @Test
    fun `side-effect-free call runs directly in every level`() = runBlocking {
        for (level in PermissionLevel.entries) {
            val registry = ToolRegistry(listOf(provider(sideEffect = false) { "pong" }))

            val result = executor(registry, level).execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

            assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AUTO_ALLOWED, result.status)
            assertEquals("pong", result.text)
        }
    }

    @Test
    fun `full access runs side-effecting tools directly`() = runBlocking {
        val registry = ToolRegistry(listOf(provider(sideEffect = true) { "pong" }))

        val result = executor(registry, PermissionLevel.FULL_ACCESS)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AUTO_ALLOWED, result.status)
        assertEquals("pong", result.text)
    }

    @Test
    fun `auto edit runs the builtin edit tool directly`() = runBlocking {
        val registry = ToolRegistry(listOf(provider(ProviderKind.BUILTIN, "edit", sideEffect = true) { "edited" }))

        val result = executor(registry, PermissionLevel.AUTO_EDIT)
            .execute(LlmToolCall("1", "edit", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AUTO_ALLOWED, result.status)
        assertEquals("edited", result.text)
    }

    @Test
    fun `auto edit gates other side-effecting tools manually`() = runBlocking {
        val registry = ToolRegistry(listOf(provider(sideEffect = true) { "pong" }))
        val gateway = ApprovalGateway(ManualApprovalHandler { ManualApprovalResult.Approved }, null)

        val result = executor(registry, PermissionLevel.AUTO_EDIT, gateway)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_APPROVED, result.status)
    }

    @Test
    fun `auto edit does not trust a remote tool named edit`() = runBlocking {
        var executions = 0
        val registry = ToolRegistry(listOf(provider(ProviderKind.MCP, "edit", sideEffect = true) { executions++; "unexpected" }))
        val gateway = ApprovalGateway(
            ManualApprovalHandler { ManualApprovalResult.Rejected("no") }, null,
        )

        val result = executor(registry, PermissionLevel.AUTO_EDIT, gateway)
            .execute(LlmToolCall("1", "mcp__test__edit", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_REJECTED, result.status)
        assertEquals(0, executions)
    }

    @Test
    fun `auto approval smart-approved call reports AI approval`() = runBlocking {
        val registry = ToolRegistry(listOf(provider(sideEffect = true) { "pong" }))
        val client = object : LlmClient {
            override fun stream(request: LlmRequest) = flowOf(
                LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "{\"allow\":true}"), "stop"),
            )
        }
        val gateway = ApprovalGateway(
            ManualApprovalHandler { error("unexpected") },
            SmallModelRef(client, "reviewer", null),
        )
        val result = executor(registry, PermissionLevel.AUTO_APPROVAL, gateway)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AI_APPROVED, result.status)
        assertEquals("pong", result.text)
    }

    @Test
    fun `auto approval without small model fails closed and reports AI rejection`() = runBlocking {
        val registry = ToolRegistry(listOf(provider(sideEffect = true) { error("must not execute") }))
        val gateway = ApprovalGateway(ManualApprovalHandler { error("unexpected") }, null)

        val result = executor(registry, PermissionLevel.AUTO_APPROVAL, gateway)
            .execute(LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context())

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AI_REJECTED, result.status)
    }

    @Test
    fun `request approval approved call executes once and preserves approval status`() = runBlocking {
        var executions = 0
        val provider = object : ToolProvider {
            override val id = "test"
            override val name = "Test"
            override val kind = ProviderKind.MCP
            override val isAvailable = true
            override fun listTools() = listOf(ProviderTool("ping", "ping", JsonObject(emptyMap()), sideEffect = true))
            override suspend fun execute(toolName: String, arguments: JsonObject): String { executions++; return "pong" }
        }
        val gateway = ApprovalGateway(ManualApprovalHandler { ManualApprovalResult.Approved }, null)
        val result = executor(ToolRegistry(listOf(provider)), PermissionLevel.REQUEST_APPROVAL, gateway).execute(
            LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context(),
        )
        assertEquals("pong", result.text)
        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_APPROVED, result.status)
        assertEquals(1, executions)
    }

    @Test
    fun `request approval denied call does not execute and reports user rejection`() = runBlocking {
        var executions = 0
        val registry = ToolRegistry(listOf(provider(sideEffect = true) { executions++; "unexpected" }))
        val gateway = ApprovalGateway(
            ManualApprovalHandler { ManualApprovalResult.Rejected("no") }, null,
        )

        val result = executor(registry, PermissionLevel.REQUEST_APPROVAL, gateway).execute(
            LlmToolCall("1", "mcp__test__ping", "{}"), ToolCallExecutor.Context(),
        )

        assertEquals(dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.USER_REJECTED, result.status)
        assertTrue(result.text.contains("no"))
        assertEquals(0, executions)
    }

    private fun executor(
        registry: ToolRegistry,
        level: PermissionLevel,
        gateway: ApprovalGateway = ApprovalGateway(ManualApprovalHandler { error("unexpected") }, null),
    ) = ToolCallExecutor(registry, gateway, permissionLevel = { level })

    private fun provider(
        kind: ProviderKind = ProviderKind.MCP,
        name: String = "ping",
        sideEffect: Boolean,
        execute: suspend () -> String,
    ) = object : ToolProvider {
        override val id = "test"
        override val name = "Test"
        override val kind = kind
        override val isAvailable = true
        override fun listTools() = listOf(ProviderTool(name, name, JsonObject(emptyMap()), sideEffect))
        override suspend fun execute(toolName: String, arguments: JsonObject) = execute()
    }
}
