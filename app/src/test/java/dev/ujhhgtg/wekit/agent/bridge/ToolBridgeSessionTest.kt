package dev.ujhhgtg.wekit.agent.bridge

import dev.ujhhgtg.wekit.agent.engine.ApprovalGateway
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalHandler
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalResult
import dev.ujhhgtg.wekit.agent.engine.ToolCallExecutor
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.agent.tool.ToolPermissionSource
import dev.ujhhgtg.wekit.agent.tool.ToolProvider
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolBridgeSessionTest {
    private val builtin = provider(ProviderKind.BUILTIN, "builtin", listOf(
        "edit", "exec", "discover_tools", "terminal_start", "load_skill", "read_only",
    ))
    private val mcp = provider(ProviderKind.MCP, "remote", listOf("lookup"))
    private val registry = ToolRegistry(ToolPermissionSource { _, _, mode -> mode }, listOf(builtin, mcp))
    private val executor = ToolCallExecutor(registry, ApprovalGateway(
        ManualApprovalHandler { ManualApprovalResult.Approved }, null,
    ))

    @Test
    fun `list exposes only non-file non-terminal direct and qualified mcp tools`() = runBlocking {
        val session = session()
        val names = kotlinx.serialization.json.Json.parseToJsonElement(session.handle("{\"op\":\"list\"}"))
            .jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(listOf("load_skill", "read_only", "mcp__remote__lookup"), names)
    }

    @Test
    fun `revoked token rejects subsequent requests`() = runBlocking {
        val session = session()
        session.revoke()
        val response = kotlinx.serialization.json.Json.parseToJsonElement(session.handle("{\"op\":\"list\"}")).jsonObject
        assertFalse(response.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("token_revoked", response.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `malformed argument shape returns machine readable error`() = runBlocking {
        val response = kotlinx.serialization.json.Json.parseToJsonElement(
            session().handle("{\"op\":\"call\",\"name\":\"read_only\",\"arguments\":7}"),
        ).jsonObject
        assertEquals("invalid_arguments", response.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `nested call executes independently and is audited`() = runBlocking {
        val audits = mutableListOf<ToolBridgeSession.AuditEntry>()
        val session = session(audits::add)
        val response = kotlinx.serialization.json.Json.parseToJsonElement(
            session.handle("{\"op\":\"call\",\"name\":\"read_only\",\"arguments\":{}}"),
        ).jsonObject
        assertTrue(response.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("read_only", response.getValue("result").jsonPrimitive.content)
        assertEquals("read_only", audits.single().tool)
    }

    private fun session(audit: (ToolBridgeSession.AuditEntry) -> Unit = {}) = ToolBridgeSession(
        registry, executor, ToolVisibility(visionTools = true, fsTools = true), EmptyCoroutineContext,
        "a".repeat(ToolBridgeProtocol.TOKEN_LENGTH), "owner", audit,
    )

    private fun provider(kind: ProviderKind, id: String, names: List<String>) = object : ToolProvider {
        override val id = id
        override val name = id
        override val kind = kind
        override val isAvailable = true
        override fun listTools() = names.map { ProviderTool(it, "$it description", JsonObject(emptyMap()), ToolMode.ENABLED) }
        override suspend fun execute(toolName: String, arguments: JsonObject) = toolName
    }
}
