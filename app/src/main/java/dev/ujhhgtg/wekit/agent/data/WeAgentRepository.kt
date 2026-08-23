package dev.ujhhgtg.wekit.agent.data

import dev.ujhhgtg.wekit.utils.fs.asPath
import androidx.room.withTransaction
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository.getExternalServiceKey
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository.permissionCache
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ExternalServiceEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageRole
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolPermissionEntity
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.model.local.LocalLlama
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentDeletionPlan
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentSessionState
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentSessionTransition
import dev.ujhhgtg.wekit.agent.environment.toSnapshot
import dev.ujhhgtg.wekit.agent.environment.NATIVE_ENVIRONMENT_ID
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.agent.tool.ToolPermissionSource
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central data access + first-run seeding for WeAgent. Holds an in-memory permission cache so the
 * synchronous [ToolPermissionSource] used by the tool registry never blocks on the DB.
 */
object WeAgentRepository : ToolPermissionSource {

    private const val TAG = "WeAgentRepository"

    /**
     * Separator packing a TOOL row's owning tool-call id in front of its payload
     * (`"<rowId><SEP><resultText>"`). NUL can't occur in either half, so the split is unambiguous.
     */
    private const val TOOL_PAYLOAD_SEP = '\u0000'

    private val db get() = WeAgentDatabase.instance

    // (providerId, toolName) -> mode. Kept in sync with the tool_permissions table.
    private val permissionCache = ConcurrentHashMap<String, ToolMode>()

    private fun key(providerId: String, toolName: String) = "$providerId\u0000$toolName"

    // --- ToolPermissionSource ---

    override fun modeFor(providerId: String, toolName: String, factoryDefault: ToolMode): ToolMode =
        permissionCache[key(providerId, toolName)] ?: factoryDefault

    suspend fun appendBridgeToolAudit(entry: dev.ujhhgtg.wekit.agent.bridge.ToolBridgeSession.AuditEntry) {
        db.bridgeToolAuditDao().insert(
            dev.ujhhgtg.wekit.agent.data.entity.BridgeToolAuditEntity(
                id = java.util.UUID.randomUUID().toString(),
                sessionId = entry.sessionId,
                environmentId = entry.environmentId,
                parentToolCallId = entry.parentToolCallId,
                providerId = entry.providerId,
                toolName = entry.tool,
                argumentsJson = entry.argumentsJson,
                approvalStatus = entry.approvalStatus,
                executionOutcome = entry.executionOutcome,
                result = entry.result,
                executedAt = java.time.Instant.now(),
            )
        )
    }

    /**
     * Idempotent first-run/every-launch seeding:
     *  - ensures the builtin provider row exists,
     *  - inserts a factory-default [ToolPermissionEntity] for every builtin tool that has no row yet
     *    (never overwrites a value the user already changed),
     *  - loads all permissions into [permissionCache].
     */
    suspend fun seedAndLoad() {
        runCatching {
            val providerDao = db.providerDao()
            val permDao = db.toolPermissionDao()

            // One row per built-in provider (builtin-wechat / builtin-wechat-sql / builtin-fs).
            for (provider in BuiltinToolProvider.all) {
                if (providerDao.getById(provider.id) == null) {
                    providerDao.upsert(
                        ProviderEntity(
                            id = provider.id,
                            kind = ProviderKind.BUILTIN,
                            name = provider.name,
                            transport = null,
                            endpointUrl = null,
                            headersJson = null,
                            enabled = true,
                        )
                    )
                }
                val existing = permDao.getForProvider(provider.id).associateBy { it.toolName }
                val toInsert = provider.seedInfos()
                    .filter { it.name !in existing }
                    .map { ToolPermissionEntity(provider.id, it.name, it.defaultMode) }
                if (toInsert.isNotEmpty()) permDao.upsertAll(toInsert)
            }

            // Load full cache
            permissionCache.clear()
            permDao.getAll().forEach { permissionCache[key(it.providerId, it.toolName)] = it.mode }
            WeLogger.i(TAG, "seeded; ${permissionCache.size} tool permissions loaded")
        }.onFailure { WeLogger.e(TAG, "seedAndLoad failed", it) }
    }

    /** Updates a tool's permission both in the DB and the in-memory cache. */
    suspend fun setToolMode(providerId: String, toolName: String, mode: ToolMode) {
        db.toolPermissionDao().upsert(ToolPermissionEntity(providerId, toolName, mode))
        permissionCache[key(providerId, toolName)] = mode
    }

    /**
     * Registers factory defaults for an MCP provider's tools.
     *
     * Remote tools seed as [ToolMode.MANUAL_APPROVAL], not ENABLED: the server decides what its
     * tools do *and* what they are called and described, and those descriptions go verbatim into the
     * model's context — so with a MESSAGE trigger a third party's chat message could otherwise steer
     * a destructive remote tool with no approval card ever shown. Built-in side-effecting tools are
     * gated the same way. The user can promote individual tools in the MCP server detail screen.
     *
     * Only tools with no row yet are seeded, so a mode the user already picked is never rewritten.
     */
    suspend fun seedMcpTools(providerId: String, toolNames: List<String>) {
        val permDao = db.toolPermissionDao()
        val existing = permDao.getForProvider(providerId).associateBy { it.toolName }
        val toInsert = toolNames.filter { it !in existing }
            .map { ToolPermissionEntity(providerId, it, ToolMode.MANUAL_APPROVAL) }
        if (toInsert.isNotEmpty()) {
            permDao.upsertAll(toInsert)
            toInsert.forEach { permissionCache[key(it.providerId, it.toolName)] = it.mode }
        }
    }

    // --- Passthrough flows for UI (settings) ---

    fun observeProviders(): Flow<List<ProviderEntity>> = db.providerDao().observeAll()

    /** One-shot read of all tool providers (builtin + MCP rows). */
    suspend fun getAllProviders(): List<ProviderEntity> = db.providerDao().getAll()

    fun observeModelProviders(): Flow<List<ModelProviderEntity>> = db.modelProviderDao().observeAll()
    fun observeModels(): Flow<List<ModelEntity>> = db.modelDao().observeAll()
    fun observeToolPermissions(): Flow<List<ToolPermissionEntity>> = db.toolPermissionDao().observeAll()

    /**
     * Stores a model provider. Its API key is persisted **as-is** (unencrypted), matching
     * [ExternalServiceEntity].
     *
     * There is deliberately no encryption layer: running WeKit at all requires root, and any key
     * WeKit could decrypt on its own it would also have to keep unlockable on-device, so a root
     * holder could recover it from the module's own storage, the request headers, memory, or a
     * dozen other surfaces. Encrypting here would only obscure the key from its owner.
     */
    suspend fun upsertModelProvider(provider: ModelProviderEntity) {
        check(provider.id != LocalLlama.PROVIDER_ID && provider.type != ModelProviderType.LOCAL_LLAMA) {
            "the local llama provider is managed by WeKit and cannot be created or edited manually"
        }
        db.modelProviderDao().upsert(provider)
    }

    /**
     * Reads a model provider, API key included. The key comes straight out of the DB — nothing is
     * decrypted, because nothing is encrypted (see [upsertModelProvider]).
     */
    suspend fun getModelProvider(id: String): ModelProviderEntity? =
        db.modelProviderDao().getById(id)

    // ---------------------------------------------------------------------------
    // Sessions & messages (Phase 7)
    // ---------------------------------------------------------------------------

    /**
     * Monotonic clock for message ordering. Room orders history by `createdAt`, and several rows can
     * be written within the same millisecond (assistant message + its tool results in one round), so
     * we hand out strictly-increasing instants instead of relying on wall-clock resolution.
     */
    private val lastStamp = AtomicLong(0L)
    private fun nextStamp(): Instant {
        val now = System.currentTimeMillis()
        val v = lastStamp.updateAndGet { prev -> if (now > prev) now else prev + 1 }
        return Instant.ofEpochMilli(v)
    }

    fun observeSessions(): Flow<List<SessionEntity>> = db.sessionDao().observeAll()
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeForSession(sessionId)

    suspend fun getSession(id: String): SessionEntity? = db.sessionDao().getById(id)

    /** One-shot read of a session's messages (used on session switch). */
    suspend fun getMessages(sessionId: String): List<MessageEntity> =
        db.messageDao().getForSession(sessionId)

    /** Records a model-visible environment transition exactly once for a session. */
    suspend fun announceEffectiveLinuxEnvironment(sessionId: String, environment: dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot, previousEnvironment: dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot? = null): Boolean =
        db.withTransaction {
            val session = db.sessionDao().getById(sessionId) ?: return@withTransaction false
            if (session.lastEffectiveLinuxEnvironmentId == environment.id) return@withTransaction false
            db.sessionDao().setLastEffectiveLinuxEnvironmentId(sessionId, environment.id)
            db.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.SYSTEM,
                    content = environmentReminder(previousEnvironment, environment),
                    createdAt = nextStamp(),
                )
            )
            true
        }

    private fun environmentReminder(previous: dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot?, environment: dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot): String =
        "[系统提醒] 当前 Linux 环境已切换为「${environment.displayName}」(${environment.type.name})。" +
            "工作目录：${environment.workingDirectory}；Shell：${environment.shell}；" +
            "架构：${environment.architecture}；权限边界：${environment.privilegesAndCapabilities}；" +
            "invoke_tool：${environment.bridgeLocation ?: "不可用"}；旧环境：${previous?.let { "${it.displayName} (${it.type.name})，工作目录：${it.workingDirectory}；Shell：${it.shell}；架构：${it.architecture}；权限边界：${it.privilegesAndCapabilities}；invoke_tool：${it.bridgeLocation ?: "不可用"}" } ?: "未设置"}。"

    /** One tool-call row by its call id (used to restore tool name / status on UI reload). */
    suspend fun getToolCall(callId: String): ToolCallEntity? =
        db.toolCallDao().getById(callId)

    /** First configured model id, if any (used to seed a new session when no default is set). */
    suspend fun firstModelId(): String? = db.modelDao().first()?.id

    /** Creates a new session with a placeholder title; returns its id. modelId null = "默认" (follow settings default). */
    suspend fun createSession(modelId: String?, systemPromptId: String?): String {
        val now = nextStamp()
        val id = UUID.randomUUID().toString()
        db.sessionDao().upsert(
            SessionEntity(
                id = id,
                title = "新对话",
                systemPromptId = systemPromptId,
                linuxEnvironmentId = null,
                lastEffectiveLinuxEnvironmentId = null,
                modelId = modelId,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun renameSession(id: String, title: String) =
        db.sessionDao().rename(id, title, nextStamp())

    suspend fun deleteSession(id: String) {
        // tool_calls reference messages by messageId; clear messages then the session.
        db.messageDao().deleteForSession(id)
        // SESSION-scoped triggers belong to this session — deleting it invalidates them.
        db.triggerDao().deleteForSession(id)
        db.sessionDao().deleteById(id)
        pendingToolCallRowIds.remove(id)
    }

    /**
     * Removes any trailing invalid assistant turns from [sessionId] before a new turn begins.
     *
     * An assistant row is considered invalid (and therefore unsafe to send to the API) when:
     *  - it requested tool calls but at least one result is still pending ([resultJson] == null),
     *    which would produce an unbalanced `tool_use` / `tool_result` pair; or
     *  - it has no text content *and* no tool calls — i.e. a thinking-only row whose content block
     *    would be empty, which most APIs reject.
     *
     * Each offending row, together with every message that came after it (partial tool-result
     * TOOL rows) and its own [ToolCallEntity] children, is deleted. The loop repeats until the
     * tail is clean. Returns true if any rows were removed.
     */
    suspend fun sanitizeSessionHistory(sessionId: String): Boolean {
        var removed = false
        while (true) {
            val rows = db.messageDao().getForSession(sessionId)
            val lastMsg = rows.lastOrNull { it.role != MessageRole.SYSTEM } ?: break
            if (lastMsg.role != MessageRole.ASSISTANT) break

            val toolCalls = db.toolCallDao().getForMessage(lastMsg.id)
            val hasPendingToolCall = toolCalls.any { it.resultJson == null }
            val isThinkingOnly = lastMsg.content.isEmpty() && toolCalls.isEmpty()

            if (!hasPendingToolCall && !isThinkingOnly) break // tail is valid

            WeLogger.w(TAG, "sanitize: removing incomplete assistant row ${lastMsg.id} (pending=$hasPendingToolCall, thinkingOnly=$isThinkingOnly)")
            // Delete the offending row and every subsequent message (partial TOOL result rows).
            db.messageDao().deleteFromTimestamp(sessionId, lastMsg.createdAt)
            // Remove the tool_call children that were orphaned by the deletion above.
            db.toolCallDao().deleteForMessage(lastMsg.id)
            removed = true
        }
        return removed
    }

    /**
     * Permanently deletes all messages after [afterTimestamp] in [sessionId] (回到此处). Their
     * [ToolCallEntity] children are removed first, then the messages themselves, so no orphaned
     * rows are left behind.
     */
    suspend fun truncateToMessage(sessionId: String, afterTimestamp: Instant) {
        db.toolCallDao().deleteForMessagesAfter(sessionId, afterTimestamp)
        db.messageDao().deleteAfterTimestamp(sessionId, afterTimestamp)
        touchSession(sessionId)
    }

    /**
     * Creates a branch of [sourceSessionId] containing all messages up to and including the one
     * at [upToTimestamp]. Session metadata (model, system prompt, Linux environment, favorite) is copied;
     * token usage and triggers are not. Returns the new session id. The caller is responsible for
     * switching the foreground to the new session.
     *
     * Tool call ids are remapped to fresh UUIDs so the copies don't collide with the originals in
     * the [tool_calls] table. TOOL message ids and content are updated to match the new call ids.
     */
    suspend fun branchSession(sourceSessionId: String, upToTimestamp: Instant): String {
        val source = db.sessionDao().getById(sourceSessionId)
            ?: error("source session $sourceSessionId not found")
        val now = nextStamp()
        val newSessionId = UUID.randomUUID().toString()

        db.sessionDao().upsert(
            SessionEntity(
                id = newSessionId,
                title = "[分支] ${source.title}",
                systemPromptId = source.systemPromptId,
                linuxEnvironmentId = source.linuxEnvironmentId,
                lastEffectiveLinuxEnvironmentId = source.lastEffectiveLinuxEnvironmentId,
                modelId = source.modelId,
                favorite = source.favorite,
                createdAt = now,
                updatedAt = now,
            )
        )

        val messages = db.messageDao().getUpToTimestamp(sourceSessionId, upToTimestamp)

        // oldCallId -> newCallId: built as we encounter ASSISTANT rows so TOOL rows can remap.
        val callIdMap = mutableMapOf<String, String>()

        for (m in messages) {
            when (m.role) {
                MessageRole.ASSISTANT -> {
                    val newMsgId = UUID.randomUUID().toString()
                    db.messageDao().insert(
                        MessageEntity(newMsgId, newSessionId, m.role, m.content, m.createdAt, m.reasoning)
                    )
                    // Copy tool_calls with fresh ids so they don't collide with the originals.
                    for (tc in db.toolCallDao().getForMessage(m.id)) {
                        val newCallId = UUID.randomUUID().toString()
                        callIdMap[tc.id] = newCallId
                        db.toolCallDao().upsert(tc.copy(id = newCallId, messageId = newMsgId))
                    }
                }

                MessageRole.TOOL -> {
                    // TOOL message ids are "tool_<callId>" and content is
                    // "<callId>${TOOL_PAYLOAD_SEP}<result>". Both must use the remapped call id.
                    val oldCallId = m.id.removePrefix("tool_")
                    val newCallId = callIdMap[oldCallId] ?: UUID.randomUUID().toString()
                    val sepIdx = m.content.indexOf(TOOL_PAYLOAD_SEP)
                    val resultText = if (sepIdx >= 0) m.content.substring(sepIdx + 1) else m.content
                    db.messageDao().insert(
                        MessageEntity(
                            id = "tool_$newCallId",
                            sessionId = newSessionId,
                            role = m.role,
                            content = "$newCallId$TOOL_PAYLOAD_SEP$resultText",
                            createdAt = m.createdAt,
                        )
                    )
                }

                else -> {
                    // USER and SYSTEM rows copied verbatim with a fresh id.
                    db.messageDao().insert(
                        MessageEntity(UUID.randomUUID().toString(), newSessionId, m.role, m.content, m.createdAt, m.reasoning)
                    )
                }
            }
        }

        return newSessionId
    }

    /** Toggles a session's favorite (starred) flag; favorited sessions pin to the top and can't be deleted. */
    suspend fun setFavorite(id: String, favorite: Boolean) = db.sessionDao().setFavorite(id, favorite)

    /** Binds (or clears, modelId=null → "默认") the session's model. */
    suspend fun updateSessionModel(id: String, modelId: String?) {
        val s = db.sessionDao().getById(id) ?: return
        db.sessionDao().upsert(s.copy(modelId = modelId, updatedAt = nextStamp()))
    }

    /**
     * Persists the latest token usage for a session so the usage strip survives session switches and
     * WeChat restarts. Deliberately does NOT bump `updatedAt` — usage is a side effect of a turn and
     * shouldn't reorder the session drawer.
     */
    suspend fun updateSessionUsage(id: String, usage: dev.ujhhgtg.wekit.agent.model.LlmUsage?) {
        db.sessionDao().updateUsage(
            id = id,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
        )
    }

    /** Persists the resolved context window (tokens) of the model used for a session's turn. */
    suspend fun updateSessionContextWindow(id: String, contextWindow: Int?) {
        db.sessionDao().updateContextWindow(id, contextWindow)
    }

    suspend fun updateSessionSystemPrompt(id: String, systemPromptId: String?) {
        val s = db.sessionDao().getById(id) ?: return
        db.sessionDao().upsert(s.copy(systemPromptId = systemPromptId, updatedAt = nextStamp()))
    }

    suspend fun updateSessionLinuxEnvironment(id: String, environmentId: String?) {
        if (environmentId != null) requireEnvironmentExists(environmentId)
        val session = db.sessionDao().getById(id) ?: return
        db.sessionDao().upsert(
            session.copy(
                linuxEnvironmentId = environmentId,
                lastEffectiveLinuxEnvironmentId = null,
                updatedAt = nextStamp(),
            )
        )
    }

    // --- write paths used by the engine's HistorySink ---

    /**
     * sessionId -> (provider wire call id -> row ids awaiting a result, in call order).
     *
     * Tool-call **rows** are keyed by a locally-minted UUID, never by the id the model sent: several
     * provider adapters synthesise ids that repeat across rounds (`call_<toolName>` when the server
     * omits ids, `call_<index>` restarting at `call_0` every round), so persisting under the wire id
     * made round 2 upsert over round 1's row and its `tool_<callId>` TOOL message REPLACE round 1's
     * — silently truncating the transcript on reload.
     *
     * [appendAssistantMessage] mints the row id and registers it here; [appendToolResult] pops it
     * back out to find the row to complete. The wire id stays purely in-memory (in [LlmToolCall]),
     * which is all it is needed for — matching results to calls inside one turn. A deque per wire id
     * keeps the pairing right even when one round issues the same colliding id twice; the engine
     * executes calls in order, so FIFO matches.
     */
    private val pendingToolCallRowIds = ConcurrentHashMap<String, MutableMap<String, ArrayDeque<String>>>()

    private fun registerToolCallRowId(sessionId: String, wireCallId: String, rowId: String) {
        val perSession = pendingToolCallRowIds.computeIfAbsent(sessionId) { HashMap() }
        synchronized(perSession) { perSession.getOrPut(wireCallId) { ArrayDeque() }.addLast(rowId) }
    }

    private fun takeToolCallRowId(sessionId: String, wireCallId: String): String? {
        val perSession = pendingToolCallRowIds[sessionId] ?: return null
        return synchronized(perSession) {
            val queue = perSession[wireCallId] ?: return@synchronized null
            val rowId = queue.removeFirstOrNull()
            if (queue.isEmpty()) perSession.remove(wireCallId)
            rowId
        }
    }

    suspend fun appendUserMessage(sessionId: String, content: String): String {
        val id = UUID.randomUUID().toString()
        db.messageDao().insert(MessageEntity(id, sessionId, MessageRole.USER, content, nextStamp()))
        touchSession(sessionId)
        return id
    }

    suspend fun appendAssistantMessage(
        sessionId: String,
        content: String?,
        reasoning: String?,
        reasoningSignature: String?,
        toolCalls: List<LlmToolCall>,
    ): String {
        val msgId = UUID.randomUUID().toString()
        db.messageDao().insert(
            MessageEntity(
                id = msgId,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = content.orEmpty(),
                createdAt = nextStamp(),
                reasoning = reasoning?.takeIf { it.isNotBlank() },
                reasoningSignature = reasoningSignature?.takeIf { it.isNotBlank() },
            )
        )
        // Any tool call left unresolved from a previous round never will be — the engine resolves
        // every call of round N before round N+1's assistant message arrives. Drop them so the map
        // can't grow across a long session.
        pendingToolCallRowIds.remove(sessionId)
        // Persist tool calls as pending children; results filled in by appendToolResult.
        toolCalls.forEach { tc ->
            val rowId = UUID.randomUUID().toString()
            registerToolCallRowId(sessionId, tc.id, rowId)
            db.toolCallDao().upsert(
                ToolCallEntity(
                    id = rowId,
                    messageId = msgId,
                    provider = "",
                    toolName = tc.name,
                    argumentsJson = tc.argumentsJson,
                    resultJson = null,
                    approvalStatus = dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus.AUTO_ALLOWED,
                    approvalReason = null,
                    executedAt = null,
                    providerSignature = tc.providerSignature?.takeIf { it.isNotBlank() },
                )
            )
        }
        touchSession(sessionId)
        return msgId
    }

    suspend fun appendToolResult(
        sessionId: String,
        callId: String,
        toolName: String,
        providerId: String,
        argumentsJson: String,
        resultText: String,
        status: dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus,
    ) {
        val rowId = takeToolCallRowId(sessionId, callId)
            ?: recoverToolCallRowId(sessionId, callId, toolName)
            ?: UUID.randomUUID().toString()

        // Update the row created by appendAssistantMessage in place, preserving its messageId link.
        // A rebuilt entity would reset every column not passed here to its default — that is how
        // providerSignature (the Gemini 3 thoughtSignature whose absence 400s the next request) used
        // to be wiped moments after being stored.
        val existing = db.toolCallDao().getById(rowId)
        db.toolCallDao().upsert(
            existing?.copy(
                provider = providerId,
                resultJson = resultText,
                approvalStatus = status,
                executedAt = nextStamp(),
            ) ?: ToolCallEntity(
                id = rowId,
                messageId = rowId,
                provider = providerId,
                toolName = toolName,
                argumentsJson = argumentsJson,
                resultJson = resultText,
                approvalStatus = status,
                // Nothing carries the rejection reason into this method (AgentSessionEngine
                // .HistorySink.onToolResult has no such parameter), so the column stays null and the
                // reason survives only inside resultJson.
                approvalReason = null,
                executedAt = nextStamp(),
            )
        )
        // … and record a TOOL message so history reconstruction can emit the tool result in order.
        db.messageDao().insert(
            MessageEntity(
                id = "tool_$rowId",
                sessionId = sessionId,
                role = MessageRole.TOOL,
                content = "$rowId$TOOL_PAYLOAD_SEP$resultText", // row id + payload
                createdAt = nextStamp(),
            )
        )
        touchSession(sessionId)
    }

    /**
     * Fallback row lookup for when [pendingToolCallRowIds] has no entry for a wire call id — the
     * process was restarted mid-turn, or the row predates locally-minted ids (legacy rows are keyed
     * by the wire id itself). Picks the oldest still-pending call of the same tool on the session's
     * last assistant message.
     */
    private suspend fun recoverToolCallRowId(
        sessionId: String,
        callId: String,
        toolName: String,
    ): String? {
        db.toolCallDao().getById(callId)?.let { return it.id } // legacy row keyed by the wire id
        val lastAssistant = db.messageDao().getForSession(sessionId)
            .lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
        return db.toolCallDao().getForMessage(lastAssistant.id)
            .firstOrNull { it.resultJson == null && it.toolName == toolName }
            ?.id
    }

    private suspend fun touchSession(sessionId: String) {
        val s = db.sessionDao().getById(sessionId) ?: return
        db.sessionDao().upsert(s.copy(updatedAt = nextStamp()))
    }

    /**
     * Reconstructs the provider-neutral [LlmMessage] history for a session (excluding the system
     * message, which the engine composes per turn). Assistant tool calls are re-attached from the
     * tool_calls table; TOOL rows are decoded from their `<rowId>${TOOL_PAYLOAD_SEP}<payload>` content.
     */
    suspend fun loadHistory(sessionId: String): List<LlmMessage> {
        val rows = db.messageDao().getForSession(sessionId)
        val out = ArrayList<LlmMessage>(rows.size)
        for (m in rows) {
            when (m.role) {
                MessageRole.USER -> out += LlmMessage(role = LlmRole.USER, content = m.content)
                MessageRole.SYSTEM -> out += LlmMessage(role = LlmRole.USER, content = m.content)
                MessageRole.ASSISTANT -> {
                    val calls = db.toolCallDao().getForMessage(m.id).map {
                        LlmToolCall(it.id, it.toolName, it.argumentsJson, providerSignature = it.providerSignature)
                    }
                    out += LlmMessage(
                        role = LlmRole.ASSISTANT,
                        content = m.content.ifEmpty { null },
                        reasoning = m.reasoning,
                        reasoningSignature = m.reasoningSignature,
                        toolCalls = calls,
                    )
                }

                MessageRole.TOOL -> {
                    val idx = m.content.indexOf(TOOL_PAYLOAD_SEP)
                    val callId = if (idx >= 0) m.content.substring(0, idx) else m.id.removePrefix("tool_")
                    val payload = if (idx >= 0) m.content.substring(idx + 1) else m.content
                    out += LlmMessage(role = LlmRole.TOOL, content = payload, toolCallId = callId)
                }
            }
        }
        return out
    }

    // --- config resolution (Phase 7) ---

    suspend fun getModel(modelId: String): ModelEntity? = db.modelDao().getById(modelId)

    /** The bound system prompt's content for a session, or null. */
    suspend fun getSystemPromptContent(systemPromptId: String?): String? =
        systemPromptId?.let { db.systemPromptDao().getById(it)?.content }

    /** All currently-enabled per-turn prompt contents (global switches). */
    suspend fun getEnabledPerTurnPrompts(): List<PerTurnPromptEntity> = db.perTurnPromptDao().getEnabled()

    /** All currently-enabled conditional prompts (global switches). */
    suspend fun getEnabledConditionalPrompts(): List<ConditionalPromptEntity> =
        db.conditionalPromptDao().getEnabled()

    fun observeSystemPrompts(): Flow<List<SystemPromptEntity>> = db.systemPromptDao().observeAll()
    fun observePerTurnPrompts(): Flow<List<PerTurnPromptEntity>> = db.perTurnPromptDao().observeAll()
    fun observeConditionalPrompts(): Flow<List<ConditionalPromptEntity>> = db.conditionalPromptDao().observeAll()
    fun observePresetPrompts(): Flow<List<PresetPromptEntity>> = db.presetPromptDao().observeAll()
    fun observeLinuxEnvironments(): Flow<List<LinuxEnvironmentEntity>> =
        db.linuxEnvironmentDao().observeAll()

    suspend fun getLinuxEnvironment(id: String): LinuxEnvironmentEntity? =
        if (id == NATIVE_ENVIRONMENT_ID) null else db.linuxEnvironmentDao().getById(id)

    fun observeLinuxEnvironment(id: String): Flow<LinuxEnvironmentEntity?> =
        if (id == NATIVE_ENVIRONMENT_ID) flowOf(null) else db.linuxEnvironmentDao().observeById(id)

    suspend fun getAllLinuxEnvironments(): List<LinuxEnvironmentEntity> =
        db.linuxEnvironmentDao().getAllOnce()

    fun observeDefaultLinuxEnvironmentId(): Flow<String> = combine(
        db.settingDao().observeValue(WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID),
        db.linuxEnvironmentDao().observeAll(),
    ) { configured, environments ->
        resolveEffectiveLinuxEnvironmentId(null, configured, environments.mapTo(HashSet()) { it.id })
    }

    fun observeSessionEffectiveLinuxEnvironmentId(sessionId: String): Flow<String> = combine(
        db.sessionDao().observeLinuxEnvironmentId(sessionId),
        db.settingDao().observeValue(WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID),
        db.linuxEnvironmentDao().observeAll(),
    ) { sessionEnvironmentId, defaultEnvironmentId, environments ->
        resolveEffectiveLinuxEnvironmentId(
            sessionEnvironmentId,
            defaultEnvironmentId,
            environments.mapTo(HashSet()) { it.id },
        )
    }

    suspend fun getEffectiveLinuxEnvironmentId(sessionId: String): String {
        val sessionIdValue = db.sessionDao().getById(sessionId)?.linuxEnvironmentId
        val defaultId = WeAgentSettings.defaultLinuxEnvironmentId()
        return resolveEffectiveLinuxEnvironmentId(
            sessionIdValue,
            defaultId,
            db.linuxEnvironmentDao().getAllOnce().mapTo(HashSet()) { it.id },
        )
    }

    suspend fun setDefaultLinuxEnvironmentId(id: String) {
        requireEnvironmentExists(id)
        WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID, id)
    }

    // ---------------------------------------------------------------------------
    // Settings-screen CRUD (Phase 8)
    // ---------------------------------------------------------------------------

    /**
     * Transactionally deletes a model provider together with its models, every session model
     * binding pointing at those models, and any settings default (default/small model) that
     * referenced one of them. In-memory caches and the live UI selection are updated after the
     * transaction commits.
     */
    suspend fun deleteModelProvider(id: String) {
        check(id != LocalLlama.PROVIDER_ID) {
            "the local llama provider is built in and cannot be deleted"
        }
        val modelIds = db.modelDao().getForProviderOnce(id).map { it.id }.toSet()
        val settingKeys = WeAgentSettings.modelDefaultKeysFor(modelIds)
        db.withTransaction {
            if (modelIds.isNotEmpty()) db.sessionDao().clearModelBindings(modelIds.toList())
            settingKeys.forEach { db.settingDao().delete(it) }
            db.modelDao().deleteForProvider(id)
            db.modelProviderDao().deleteById(id)
        }
        settingKeys.forEach { WeAgentSettings.clearCached(it) }
        dev.ujhhgtg.wekit.agent.model.ModelProviderManager.invalidate(id)
        notifyModelsDeleted(modelIds)
    }

    suspend fun upsertModel(model: ModelEntity) {
        db.withTransaction {
            val current = db.modelDao().getById(model.id)
            if (model.providerId == LocalLlama.PROVIDER_ID || current?.providerId == LocalLlama.PROVIDER_ID) {
                check(current != null && current.providerId == LocalLlama.PROVIDER_ID &&
                        model.providerId == LocalLlama.PROVIDER_ID) {
                    "local llama models are package-managed and cannot be added or reassigned manually"
                }
                db.modelDao().upsert(
                    current.copy(
                        reasoningEffort = model.reasoningEffort,
                        contextWindow = model.contextWindow,
                    )
                )
            } else {
                db.modelDao().upsert(model)
            }
        }
    }

    /**
     * Transactionally deletes one model, clearing every session model binding and any settings
     * default that referenced it. No-op when the row is already gone (double delete).
     */
    suspend fun deleteModel(id: String) {
        val model = db.modelDao().getById(id) ?: return
        check(model.providerId != LocalLlama.PROVIDER_ID) {
            "local llama models are package-managed and cannot be deleted manually"
        }
        deleteStoredModel(model)
    }

    /** Package sync-only stale-row deletion that retains the normal model-deletion cascade. */
    internal suspend fun deleteLocalLlamaModelForSync(id: String) {
        val model = db.modelDao().getById(id) ?: return
        check(model.providerId == LocalLlama.PROVIDER_ID) {
            "sync deletion is restricted to local llama model rows"
        }
        deleteStoredModel(model)
    }

    private suspend fun deleteStoredModel(model: ModelEntity) {
        val settingKeys = WeAgentSettings.modelDefaultKeysFor(setOf(model.id))
        db.withTransaction {
            db.sessionDao().clearModelBindings(listOf(model.id))
            settingKeys.forEach { db.settingDao().delete(it) }
            db.modelDao().deleteById(model.id)
        }
        settingKeys.forEach { WeAgentSettings.clearCached(it) }
        notifyModelsDeleted(setOf(model.id))
    }

    /** Outcome of [importModels]: how many rows were newly added vs overwritten in place. */
    data class ModelImportResult(val added: Int, val overwritten: Int)

    /**
     * Bulk-imports [remoteIds] as models under [providerId] in one transaction. An id already
     * present for this provider keeps its local row id but is reset to a freshly-imported state
     * (displayName = remote id, no reasoning gear, no custom override/context/max-token values,
     * no vision); new ids get fresh UUIDs. Returns separate added/overwritten counts.
     */
    suspend fun importModels(providerId: String, remoteIds: List<String>): ModelImportResult {
        check(providerId != LocalLlama.PROVIDER_ID) {
            "local llama models are derived from installed packages and cannot be imported manually"
        }
        var added = 0
        var overwritten = 0
        db.withTransaction {
            val existing = db.modelDao().getForProviderOnce(providerId).associateBy { it.modelIdRemote }
            for (remoteId in remoteIds) {
                val cur = existing[remoteId]
                if (cur == null) {
                    db.modelDao().upsert(
                        ModelEntity(
                            id = UUID.randomUUID().toString(),
                            providerId = providerId,
                            modelIdRemote = remoteId,
                            reasoningEffort = null,
                            customJsonOverride = null,
                            displayName = remoteId,
                        )
                    )
                    added++
                } else {
                    db.modelDao().upsert(
                        cur.copy(
                            displayName = remoteId,
                            reasoningEffort = null,
                            customJsonOverride = null,
                            contextWindow = null,
                            maxTokens = null,
                            supportsVision = false,
                        )
                    )
                    overwritten++
                }
            }
        }
        return ModelImportResult(added, overwritten)
    }

    fun observeModelsForProvider(providerId: String): Flow<List<ModelEntity>> =
        db.modelDao().observeForProvider(providerId)

    /**
     * Best-effort live-UI refresh after model rows were deleted (models or a whole provider).
     * Only the Compose selection state is cleared — the DB is already committed at this point, so
     * a failure here must not roll anything back.
     */
    private fun notifyModelsDeleted(modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        runCatching {
            dev.ujhhgtg.wekit.features.api.agent.WeAgentService.onModelsDeleted(modelIds)
        }
    }

    /** Upserts an MCP server row (kind=MCP) and re-syncs the client manager. */
    suspend fun upsertMcpProvider(provider: ProviderEntity) {
        db.providerDao().upsert(provider)
        dev.ujhhgtg.wekit.agent.mcp.McpClientManager.sync()
    }

    suspend fun deleteMcpProvider(id: String) {
        db.providerDao().deleteById(id)
        db.toolPermissionDao().deleteForProvider(id)
        dev.ujhhgtg.wekit.agent.mcp.McpClientManager.sync()
    }

    suspend fun upsertSystemPrompt(p: SystemPromptEntity) = db.systemPromptDao().upsert(p)

    /**
     * Transactionally deletes a system prompt, clearing every session binding and the settings
     * default that referenced it. No-op when the row is already gone (double delete).
     */
    suspend fun deleteSystemPrompt(id: String) {
        db.systemPromptDao().getById(id) ?: return
        val settingKeys = WeAgentSettings.systemPromptDefaultKeysFor(setOf(id))
        db.withTransaction {
            db.sessionDao().clearSystemPromptBindings(id)
            settingKeys.forEach { db.settingDao().delete(it) }
            db.systemPromptDao().deleteById(id)
        }
        settingKeys.forEach { WeAgentSettings.clearCached(it) }
        runCatching { dev.ujhhgtg.wekit.features.api.agent.WeAgentService.onSystemPromptDeleted(id) }
    }
    suspend fun getAllSystemPromptsOnce(): List<SystemPromptEntity> = db.systemPromptDao().getAllOnce()

    suspend fun upsertPerTurnPrompt(p: PerTurnPromptEntity) = db.perTurnPromptDao().upsert(p)
    suspend fun deletePerTurnPrompt(id: String) = db.perTurnPromptDao().deleteById(id)

    suspend fun upsertConditionalPrompt(p: ConditionalPromptEntity) = db.conditionalPromptDao().upsert(p)
    suspend fun deleteConditionalPrompt(id: String) = db.conditionalPromptDao().deleteById(id)

    suspend fun upsertPresetPrompt(p: PresetPromptEntity) = db.presetPromptDao().upsert(p)
    suspend fun deletePresetPrompt(id: String) = db.presetPromptDao().deleteById(id)

    suspend fun upsertLinuxEnvironment(environment: LinuxEnvironmentEntity) {
        require(environment.id != NATIVE_ENVIRONMENT_ID) { "native environment is built in" }
        validateLinuxEnvironment(environment)
        db.withTransaction {
            val existing = db.linuxEnvironmentDao().getById(environment.id)
            validateLinuxEnvironmentUpdate(existing, environment)
            db.linuxEnvironmentDao().upsert(environment)
        }
    }

    suspend fun deleteLinuxEnvironment(
        id: String,
        deletedEnvironment: EnvironmentSnapshot,
        nativeEnvironment: EnvironmentSnapshot,
    ): Boolean {
        require(id != NATIVE_ENVIRONMENT_ID) { "native environment cannot be deleted" }
        var deleted = false
        db.withTransaction {
            val existing = db.linuxEnvironmentDao().getById(id) ?: return@withTransaction
            check(existing.id == deletedEnvironment.id) { "deleted environment snapshot does not match the stored row" }
            val stored = db.linuxEnvironmentDao().getAllOnce()
            val sessions = db.sessionDao().getAllOnce()
            val deletionPlan = planLinuxEnvironmentDeletion(
                deletedEnvironment = deletedEnvironment,
                nativeEnvironment = nativeEnvironment,
                defaultEnvironmentId = db.settingDao().getValue(WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID),
                sessions = sessions.map {
                    LinuxEnvironmentSessionState(it.id, it.linuxEnvironmentId, it.lastEffectiveLinuxEnvironmentId)
                },
                storedEnvironmentIds = stored.mapTo(HashSet()) { it.id },
                storedEnvironmentSnapshots = stored.associate { it.id to it.toSnapshot() },
            )
            db.settingDao().upsert(
                dev.ujhhgtg.wekit.agent.data.entity.SettingEntity(
                    WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID,
                    deletionPlan.defaultEnvironmentId,
                )
            )
            deletionPlan.transitions.forEach { transition ->
                db.sessionDao().transitionLinuxEnvironment(
                    transition.sessionId,
                    transition.environmentId,
                    transition.environment.id,
                )
                db.messageDao().insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = transition.sessionId,
                        role = MessageRole.SYSTEM,
                        content = environmentReminder(transition.previousEnvironment, transition.environment),
                        createdAt = nextStamp(),
                    )
                )
            }
            deleted = db.linuxEnvironmentDao().deleteById(id) != 0
        }
        WeAgentSettings.clearCached(WeAgentSettings.KEY_DEFAULT_LINUX_ENVIRONMENT_ID)
        return deleted
    }

    suspend fun getAllModelsOnce(): List<ModelEntity> = db.modelDao().getAllOnce()

    internal fun resolveEffectiveLinuxEnvironmentId(
        sessionEnvironmentId: String?,
        defaultEnvironmentId: String?,
        storedEnvironmentIds: Set<String>,
    ): String = when {
        sessionEnvironmentId == NATIVE_ENVIRONMENT_ID -> NATIVE_ENVIRONMENT_ID
        sessionEnvironmentId != null && sessionEnvironmentId in storedEnvironmentIds -> sessionEnvironmentId
        defaultEnvironmentId == NATIVE_ENVIRONMENT_ID -> NATIVE_ENVIRONMENT_ID
        defaultEnvironmentId != null && defaultEnvironmentId in storedEnvironmentIds -> defaultEnvironmentId
        else -> NATIVE_ENVIRONMENT_ID
    }

    internal fun planLinuxEnvironmentDeletion(
        deletedEnvironment: EnvironmentSnapshot,
        nativeEnvironment: EnvironmentSnapshot,
        defaultEnvironmentId: String?,
        sessions: List<LinuxEnvironmentSessionState>,
        storedEnvironmentIds: Set<String>,
        storedEnvironmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap(),
    ): LinuxEnvironmentDeletionPlan {
        val remainingIds = storedEnvironmentIds - deletedEnvironment.id
        val replacementDefaultId = resolveEffectiveLinuxEnvironmentId(null, defaultEnvironmentId, remainingIds)
        val snapshots = storedEnvironmentSnapshots + (NATIVE_ENVIRONMENT_ID to nativeEnvironment)
        val transitions = sessions.mapNotNull { session ->
            if (session.environmentId != deletedEnvironment.id &&
                session.lastEffectiveEnvironmentId != deletedEnvironment.id &&
                !(session.environmentId == null && defaultEnvironmentId == deletedEnvironment.id)
            ) return@mapNotNull null

            val bindingId = session.environmentId?.takeUnless { it == deletedEnvironment.id }
            val effectiveId = resolveEffectiveLinuxEnvironmentId(bindingId, replacementDefaultId, remainingIds)
            LinuxEnvironmentSessionTransition(
                sessionId = session.sessionId,
                environmentId = bindingId,
                previousEnvironment = deletedEnvironment,
                environment = requireNotNull(snapshots[effectiveId]) { "replacement environment $effectiveId has no snapshot" },
            )
        }
        return LinuxEnvironmentDeletionPlan(replacementDefaultId, transitions)
    }

    internal fun validateLinuxEnvironmentUpdate(
        existing: LinuxEnvironmentEntity?,
        incoming: LinuxEnvironmentEntity,
    ) {
        require(existing == null || existing.type == incoming.type) { "environment type is immutable" }
    }

    private suspend fun requireEnvironmentExists(id: String) {
        require(id == NATIVE_ENVIRONMENT_ID || db.linuxEnvironmentDao().getById(id) != null) {
            "environment $id does not exist"
        }
    }

    private fun validateLinuxEnvironment(environment: LinuxEnvironmentEntity) {
        require(environment.name.isNotBlank()) { "environment name is required" }
        require(environment.workingDirectory.isNotBlank()) { "working directory is required" }
        dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentManager.parseEnvironmentVariables(
            environment.environmentVariablesJson,
        )
        require(environment.type != LinuxEnvironmentType.NATIVE) { "native environment is built in" }
        require(
            (environment.sshCredentialCiphertext == null) == (environment.sshCredentialIv == null)
        ) { "encrypted SSH credentials require both ciphertext and IV" }
        when (environment.type) {
            LinuxEnvironmentType.PROOT, LinuxEnvironmentType.CHROOT -> {
                require(!environment.rootfsPath.isNullOrBlank()) { "local environments require a rootfs path" }
                require(environment.sshHost == null) { "local environments cannot contain SSH configuration" }
                if (environment.type == LinuxEnvironmentType.CHROOT) {
                    dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceLayout.validatePublishedRootfs(
                        environment.rootfsPath.asPath
                    )
                }
            }
            LinuxEnvironmentType.SSH -> {
                require(environment.rootfsPath == null) { "SSH environments cannot contain a rootfs path" }
                require(!environment.sshHost.isNullOrBlank()) { "SSH host is required" }
                require(environment.sshPort != null && environment.sshPort in 1..65535) { "SSH port is invalid" }
                require(!environment.sshUsername.isNullOrBlank()) { "SSH username is required" }
                require(!environment.sshAuthenticationType.isNullOrBlank()) { "SSH authentication type is required" }
            }
            LinuxEnvironmentType.NATIVE -> error("native environment is built in")
        }
    }

    // ---------------------------------------------------------------------------
    // Triggers (WeAgent trigger system)
    // ---------------------------------------------------------------------------

    fun observeTriggers(): Flow<List<dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity>> =
        db.triggerDao().observeAll()

    suspend fun getAllTriggersOnce(): List<dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity> =
        db.triggerDao().getAllOnce()

    suspend fun getEnabledTriggers(): List<dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity> =
        db.triggerDao().getEnabled()

    suspend fun getTrigger(id: String): dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity? =
        db.triggerDao().getById(id)

    suspend fun getTriggersForSession(sessionId: String): List<dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity> =
        db.triggerDao().getForSession(sessionId)

    suspend fun upsertTrigger(trigger: dev.ujhhgtg.wekit.agent.data.entity.TriggerEntity) =
        db.triggerDao().upsert(trigger)

    suspend fun setTriggerEnabled(id: String, enabled: Boolean) =
        db.triggerDao().setEnabled(id, enabled)

    suspend fun setTriggerLastFiredAt(id: String, firedAt: Instant) =
        db.triggerDao().setLastFiredAt(id, firedAt)

    suspend fun deleteTrigger(id: String) = db.triggerDao().deleteById(id)

    // ---------------------------------------------------------------------------
    // External service API keys (network tools)
    // ---------------------------------------------------------------------------

    fun observeExternalServices(): Flow<List<ExternalServiceEntity>> =
        db.externalServiceDao().observeAll()

    suspend fun getExternalServiceKey(serviceId: String): String? =
        db.externalServiceDao().getApiKey(serviceId)

    /**
     * Persists [apiKey] for [serviceId]. Passing null or a blank key deletes the row so
     * [getExternalServiceKey] returns null for unconfigured services.
     */
    suspend fun setExternalServiceKey(serviceId: String, apiKey: String?) {
        if (apiKey.isNullOrBlank()) db.externalServiceDao().deleteById(serviceId)
        else db.externalServiceDao().upsert(ExternalServiceEntity(serviceId, apiKey))
    }
}
