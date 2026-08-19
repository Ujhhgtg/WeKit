package dev.ujhhgtg.wekit.agent.bridge

import dev.ujhhgtg.wekit.agent.engine.ApprovalGateway
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalHandler
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalResult
import dev.ujhhgtg.wekit.agent.engine.ToolCallExecutor
import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.agent.tool.ToolPermissionSource
import dev.ujhhgtg.wekit.agent.tool.ToolProvider
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun `qualified mcp tools cannot bypass bridge exclusions`() = runBlocking {
        val mcpWithReservedNames = provider(
            ProviderKind.MCP, "reserved",
            listOf("edit", "exec", "discover_tools", "terminal_custom", "lookup"),
        )
        val scopedRegistry = ToolRegistry(
            ToolPermissionSource { _, _, mode -> mode },
            listOf(builtin, mcpWithReservedNames),
        )
        val scopedExecutor = ToolCallExecutor(scopedRegistry, ApprovalGateway(
            ManualApprovalHandler { ManualApprovalResult.Approved }, null,
        ))
        val session = ToolBridgeSession(
            scopedRegistry, scopedExecutor, ToolVisibility(true, true), EmptyCoroutineContext,
            "a".repeat(ToolBridgeProtocol.TOKEN_LENGTH), "owner", {}, "native", null,
        )
        val names = kotlinx.serialization.json.Json.parseToJsonElement(session.handle("{\"op\":\"list\"}"))
            .jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue("mcp__reserved__lookup" in names)
        assertTrue(names.none { it.contains("__edit") || it.contains("__exec") ||
            it.contains("__discover_tools") || it.contains("__terminal_") })
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
        with(audits.single()) {
            assertEquals("owner", sessionId)
            assertEquals("native", environmentId)
            assertEquals("builtin", providerId)
            assertEquals("read_only", tool)
            assertEquals(ApprovalStatus.AUTO_ALLOWED, approvalStatus)
            assertEquals("SUCCEEDED", executionOutcome)
        }
    }

    @Test
    fun `terminal-lived call replaces completed parent job and preserves agent contexts`() = runBlocking {
        val completedParent = Job().also { it.complete() }
        val agentContext = AgentSessionContext("terminal-owner")
        val imageSink = UiImageSink()
        var observedJob: Job? = null
        var observedJobWasActive = false
        var observedAgentContext: AgentSessionContext? = null
        var observedImageSink: UiImageSink? = null
        val contextProvider = object : ToolProvider by builtin {
            override fun listTools() = listOf(ProviderTool("inspect", "inspect", JsonObject(emptyMap()), ToolMode.ENABLED))
            override suspend fun execute(toolName: String, arguments: JsonObject): String {
                val context = currentCoroutineContext()
                observedJob = context[Job]
                observedJobWasActive = observedJob!!.isActive
                observedAgentContext = context[AgentSessionContext]
                observedImageSink = context[UiImageSink]
                return "ok"
            }
        }
        val contextRegistry = ToolRegistry(ToolPermissionSource { _, _, mode -> mode }, listOf(contextProvider))
        val contextExecutor = ToolCallExecutor(contextRegistry, ApprovalGateway(
            ManualApprovalHandler { ManualApprovalResult.Approved }, null,
        ))
        val session = ToolBridgeSession(
            contextRegistry, contextExecutor, ToolVisibility(true, true),
            completedParent + agentContext + imageSink,
            "a".repeat(ToolBridgeProtocol.TOKEN_LENGTH), "owner", {}, "native", null,
        )

        session.handle("{\"op\":\"call\",\"name\":\"inspect\",\"arguments\":{}}")

        assertTrue(observedJobWasActive)
        assertFalse(observedJob === completedParent)
        assertTrue(observedAgentContext === agentContext)
        assertTrue(observedImageSink === imageSink)
    }

    @Test
    fun `one-shot endpoint is injected only for command lifetime`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = ToolBridgeServer(registry, { executor }, scope)
        server.start()
        lateinit var endpoint: ToolBridgeServer.Endpoint
        try {
            server.withOneShot(
                owner = "owner",
                environmentId = "native",
                parentToolCallId = "exec-call",
                visibility = ToolVisibility(true, true),
                context = EmptyCoroutineContext,
            ) {
                endpoint = it
                assertEquals(server.port.toString(), it.environment().getValue("WEAGENT_BRIDGE_PORT"))
                assertEquals(it.token, it.environment().getValue("WEAGENT_BRIDGE_TOKEN"))
                val active = kotlinx.serialization.json.Json.parseToJsonElement(
                    InvokeToolClient(it.port, it.token).list(),
                ).jsonArray
                assertTrue(active.isNotEmpty())
            }

            val revoked = kotlinx.serialization.json.Json.parseToJsonElement(
                InvokeToolClient(endpoint.port, endpoint.token).list(),
            ).jsonObject
            assertEquals("unauthorized", revoked.getValue("error").jsonPrimitive.content)
        } finally {
            server.close()
            scope.cancel()
        }
    }

    private fun session(audit: suspend (ToolBridgeSession.AuditEntry) -> Unit = {}) = ToolBridgeSession(
        registry, executor, ToolVisibility(visionTools = true, fsTools = true), EmptyCoroutineContext,
        "a".repeat(ToolBridgeProtocol.TOKEN_LENGTH), "owner", audit, "native", null,
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
