package dev.ujhhgtg.wekit.agent.bridge

import dev.ujhhgtg.wekit.agent.engine.ToolCallExecutor
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext
import dev.ujhhgtg.wekit.agent.terminal.TerminalManager
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

class ToolBridgeServer(
    private val registry: ToolRegistry,
    private val executorFactory: suspend (String) -> ToolCallExecutor,
    private val scope: CoroutineScope,
    private val audit: suspend (ToolBridgeSession.AuditEntry) -> Unit = {},
) {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, ToolBridgeSession>()
    private val connectionPermits = java.util.concurrent.Semaphore(MAX_CONNECTIONS)
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: error("bridge is not running")

    fun start(): Int {
        check(server == null)
        server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        scope.launch(Dispatchers.IO) {
            while (true) {
                val socket = runCatching { server!!.accept() }.getOrNull() ?: break
                if (!connectionPermits.tryAcquire()) socket.close() else launch(Dispatchers.IO) {
                    try { serve(socket) } finally { connectionPermits.release() }
                }
            }
        }
        return port
    }

    suspend fun open(
        owner: String,
        environmentId: String,
        parentToolCallId: String?,
        visibility: ToolVisibility,
        context: CoroutineContext,
    ): ToolBridgeSession {
        val token = ByteArray(ToolBridgeProtocol.TOKEN_BYTES).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
        return ToolBridgeSession(registry, executorFactory(owner), visibility, context, token, owner, audit,
            environmentId, parentToolCallId).also { tokens[token] = it }
    }

    fun revoke(session: ToolBridgeSession) { tokens.remove(session.token); session.revoke() }

    suspend fun <T> withOneShot(
        owner: String,
        environmentId: String,
        parentToolCallId: String?,
        visibility: ToolVisibility,
        context: CoroutineContext,
        block: suspend (Endpoint) -> T,
    ): T {
        val session = open(owner, environmentId, parentToolCallId, visibility, context)
        return try { block(Endpoint(port, session.token)) } finally { revoke(session) }
    }

    suspend fun openForTerminal(
        terminalManager: TerminalManager,
        terminalId: String,
        owner: String,
        environmentId: String,
        visibility: ToolVisibility,
        context: CoroutineContext,
    ): Pair<Endpoint, ToolBridgeSession> {
        val session = open(owner, environmentId, null, visibility, context)
        try {
            terminalManager.addRevocationHook(terminalId) { revoke(session) }
        } catch (error: Throwable) {
            revoke(session)
            throw error
        }
        return Endpoint(port, session.token) to session
    }

    data class Endpoint(val port: Int, val token: String) {
        fun environment(): Map<String, String> = mapOf(
            "WEAGENT_BRIDGE_PORT" to port.toString(),
            "WEAGENT_BRIDGE_TOKEN" to token,
        )
    }

    fun close() { server?.close(); server = null; tokens.values.forEach(ToolBridgeSession::revoke); tokens.clear() }

    private fun serve(socket: java.net.Socket) {
        socket.use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            val frame = runCatching { ToolBridgeProtocol.read(it.getInputStream()) }.getOrNull()
            if (frame == null) return
            val session = tokens[frame.token]
            val response = if (session == null) "{\"ok\":false,\"error\":\"unauthorized\"}" else
                kotlinx.coroutines.runBlocking { withTimeout(REQUEST_TIMEOUT_MS) { session.handle(frame.payload) } }
            val writeDeadline = scope.launch(Dispatchers.IO) {
                delay(SOCKET_TIMEOUT_MS.toLong())
                runCatching { it.close() }
            }
            try {
                ToolBridgeProtocol.write(it.getOutputStream(), frame.token, response)
            } finally {
                writeDeadline.cancel()
            }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 16
        const val SOCKET_TIMEOUT_MS = 10_000
        const val REQUEST_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
