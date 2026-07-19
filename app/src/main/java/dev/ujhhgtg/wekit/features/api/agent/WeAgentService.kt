package dev.ujhhgtg.wekit.features.api.agent

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.ujhhgtg.wekit.agent.data.WeAgentDatabase
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.data.entity.MessageRole
import dev.ujhhgtg.wekit.agent.engine.AgentEvent
import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.engine.AgentSessionEngine
import dev.ujhhgtg.wekit.agent.engine.ApprovalGateway
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalHandler
import dev.ujhhgtg.wekit.agent.engine.ManualApprovalResult
import dev.ujhhgtg.wekit.agent.engine.PendingApproval
import dev.ujhhgtg.wekit.agent.engine.PromptComposer
import dev.ujhhgtg.wekit.agent.engine.SmallModelRef
import dev.ujhhgtg.wekit.agent.engine.TurnConfig
import dev.ujhhgtg.wekit.agent.mcp.McpClientManager
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.agent.net.ExternalServiceId
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import dev.ujhhgtg.wekit.agent.workspace.VfsContext
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceStore
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.ballState
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.handleEvent
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.init
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.messageListState
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.messageListSyncedSessionId
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.newSession
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.pendingApproval
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.pendingApprovals
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.resolveTurnConfig
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.runTurn
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.runningTurns
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.sendMessage
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.switchSession
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.syncForeground
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.uiMessages
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.uiSessions
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The WeAgent brain: a process-level singleton coordinating persistence, the tool registry, the
 * model layer, and the [AgentSessionEngine], while exposing Compose snapshot state for the overlay
 * UI. Lives in WeChat's main process; initialized once when the [dev.ujhhgtg.wekit] WeAgent feature
 * is enabled.
 *
 * The overlay UI is intentionally thin (per project decision): it renders [uiSessions]/[uiMessages]/
 * [ballState]/[pendingApproval] and calls [sendMessage]/[newSession]/[switchSession]/… — all heavy
 * logic lives here.
 */
object WeAgentService {

    private const val TAG = "WeAgentService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Permission resolution + persistence are unified in the repository.
    private val registry = ToolRegistry(permissions = WeAgentRepository, providers = BuiltinToolProvider.all)

    // --- UI state (Compose snapshot) ---

    /** Floating-ball state machine (§1.2). */
    enum class BallState { IDLE, RUNNING, PENDING_APPROVAL, ERROR }

    val ballState = mutableStateOf(BallState.IDLE)
    val currentSessionId = mutableStateOf<String?>(null)

    /** Sessions shown in the drawer, newest first. */
    val uiSessions: SnapshotStateList<SessionRow> = mutableStateListOf()

    /** Messages of the current session, oldest first. Streaming deltas mutate the last row. */
    val uiMessages: SnapshotStateList<ChatRow> = mutableStateListOf()

    /**
     * Scroll state of the chat list, hoisted here so it survives the overlay panel being disposed
     * when the floating ball closes. If it lived inside the composable it would reset to the top on
     * every reopen (and then auto-scroll to the bottom). Kept across open/close; the UI only pulls to
     * the bottom on a genuine new row or a session switch — see [messageListSyncedSessionId].
     */
    val messageListState = androidx.compose.foundation.lazy.LazyListState()

    /**
     * The session whose newest message the [messageListState] has already been snapped to. Lets the
     * chat view land at the bottom once per session (first show / session switch) without re-snapping
     * on a plain open/close of the ball.
     */
    var messageListSyncedSessionId: String? = null

    /**
     * The manual-approval card shown for the CURRENT (foreground) session only. Mirrors
     * [pendingApprovals] for `currentSessionId`; kept as a separate state so the UI can observe one
     * value. A background session's pending approval sits in [pendingApprovals] and only surfaces here
     * once the user switches to that session.
     */
    val pendingApproval = mutableStateOf<PendingApprovalUi?>(null)

    /**
     * Per-session pending manual approvals (§ per-session approval). Keyed by session id. A background
     * session that hits a MANUAL_APPROVAL tool parks its request here and waits indefinitely; its card
     * is only rendered while that session is foreground. Accessed from both the engine coroutine (IO)
     * and the UI sync (Main), hence concurrent.
     */
    private val pendingApprovals = java.util.concurrent.ConcurrentHashMap<String, PendingApprovalUi>()

    /** Current session's bound model / system-prompt ids, for the input-bar + menu. */
    val currentModelId = mutableStateOf<String?>(null)
    val currentSystemPromptId = mutableStateOf<String?>(null)

    /** Current session's bound workspace id (null = unbound), for the input-bar + menu. */
    val currentWorkspaceId = mutableStateOf<String?>(null)

    /** Global memory-enabled flag mirrored for the input-bar + menu toggle. */
    val memoryEnabled = mutableStateOf(false)


    /** Token usage of the latest model request this session, for the usage strip (null = none yet). */
    val currentUsage = mutableStateOf<dev.ujhhgtg.wekit.agent.model.LlmUsage?>(null)

    /**
     * Context window (tokens) of the model actually used for the foreground session's current turn,
     * or null when unknown. Published by [resolveTurnConfig] so the usage strip reflects the resolved
     * "默认" model (session model → settings default → first model), not the raw session model id
     * (which is null for "默认" and would otherwise never match a model entry).
     */
    val currentContextWindow = mutableStateOf<Int?>(null)

    // --- Queued-message state (§1.2) ---

    /** What happens when the user sends while a turn is already running. */
    enum class SendWhileRunningMode { QUEUE_AFTER_TURN, QUEUE_AS_STEER }

    val sendWhileRunningMode = mutableStateOf(SendWhileRunningMode.QUEUE_AFTER_TURN)

    /**
     * Non-null while the user has queued a message waiting to be sent. The UI shows the pending text
     * in the input bar (grayed out), a cancel button replaces send, and the input is read-only until
     * the queued message is either sent or cancelled.
     */
    val queuedMessage = mutableStateOf<String?>(null)

    /** Available models / system prompts / presets / workspaces for the input-bar menus (synced with DB). */
    val availableModels: SnapshotStateList<ModelOption> = mutableStateListOf()
    val availableSystemPrompts: SnapshotStateList<SystemPromptOption> = mutableStateListOf()
    val availablePresets: SnapshotStateList<PresetOption> = mutableStateListOf()
    val availableWorkspaces: SnapshotStateList<WorkspaceOption> = mutableStateListOf()

    /**
     * [label] is "<provider name>:<model display or remote id>". [contextWindow] is the model's
     * custom context window (tokens) for the usage percentage, or null when unknown.
     */
    data class ModelOption(val id: String, val label: String, val contextWindow: Int? = null)
    data class SystemPromptOption(val id: String, val name: String)
    data class PresetOption(val id: String, val title: String, val content: String)
    data class WorkspaceOption(val id: String, val name: String)

    data class SessionRow(val id: String, val title: String, val favorite: Boolean = false)
    data class ChatRow(
        val id: String,
        val role: Role,
        var text: String,
        var reasoning: String? = null,
        /** True while reasoning tokens are still streaming; reset to false after reloadMessages. */
        var reasoningStreaming: Boolean = false,
        var toolName: String? = null,
        var toolStatus: ApprovalStatus? = null,
        /** Tool call input arguments JSON; separate from [text] which holds the result. */
        var toolInput: String? = null,
        /** Persisted [createdAt] from [dev.ujhhgtg.wekit.agent.data.entity.MessageEntity]; [java.time.Instant.EPOCH] for live-streaming rows. */
        val createdAt: java.time.Instant = java.time.Instant.EPOCH,
    ) {
        enum class Role { USER, ASSISTANT, TOOL, SYSTEM_NOTE }
    }

    data class PendingApprovalUi(
        val pending: PendingApproval,
        val deferred: CompletableDeferred<ManualApprovalResult>,
    )

    @Volatile
    private var initialized = false

    /**
     * Running turns keyed by sessionId. Multiple sessions can run concurrently (a foreground chat and
     * one or more background trigger-fired turns). The queued-message / steer mechanics remain scoped
     * to the currently-displayed session.
     */
    private val runningTurns = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun isRunning(sessionId: String): Boolean = runningTurns[sessionId]?.isActive == true

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    fun init() {
        if (initialized) return
        initialized = true
        scope.launch {
            runCatching {
                // Warm the DB, seed permissions, load settings.
                WeAgentDatabase.instance
                WeAgentRepository.seedAndLoad()
                WeAgentSettings.load()
                BuiltinToolProvider.fsToolsVisible =
                    WeAgentSettings.workspaceEnabled() || WeAgentSettings.memoryEnabled()

                // Bring up MCP providers and keep the registry's MCP set in sync.
                McpClientManager.onProvidersChanged = {
                    registry.setMcpProviders(McpClientManager.connectedProviders())
                }
                McpClientManager.sync()

                // Observe sessions for the drawer.
                launch {
                    WeAgentRepository.observeSessions().collectLatest { rows ->
                        withContext(Dispatchers.Main) {
                            uiSessions.clear()
                            uiSessions.addAll(rows.map { SessionRow(it.id, it.title, it.favorite) })
                        }
                    }
                }
                // Observe models + providers for the panel quick-switch menu. The label is
                // "<providerName>:<displayName|modelId>" (§ item 1), so we combine both flows.
                launch {
                    kotlinx.coroutines.flow.combine(
                        WeAgentRepository.observeModels(),
                        WeAgentRepository.observeModelProviders(),
                    ) { models, providers ->
                        val providerName = providers.associate { it.id to it.name }
                        models.map { m ->
                            val model = m.displayName.ifBlank { m.modelIdRemote }
                            val prefix = providerName[m.providerId]?.takeIf { it.isNotBlank() }
                            ModelOption(m.id, if (prefix != null) "$prefix:$model" else model, m.contextWindow)
                        }
                    }.collectLatest { rows ->
                        withContext(Dispatchers.Main) {
                            availableModels.clear()
                            availableModels.addAll(rows)
                        }
                    }
                }
                launch {
                    WeAgentRepository.observeSystemPrompts().collectLatest { rows ->
                        withContext(Dispatchers.Main) {
                            availableSystemPrompts.clear()
                            availableSystemPrompts.addAll(rows.map { SystemPromptOption(it.id, it.name) })
                        }
                    }
                }
                launch {
                    WeAgentRepository.observePresetPrompts().collectLatest { rows ->
                        withContext(Dispatchers.Main) {
                            availablePresets.clear()
                            availablePresets.addAll(rows.map { PresetOption(it.id, it.title, it.content) })
                        }
                    }
                }
                launch {
                    WeAgentRepository.observeWorkspaces().collectLatest { rows ->
                        withContext(Dispatchers.Main) {
                            availableWorkspaces.clear()
                            availableWorkspaces.addAll(rows.map { WorkspaceOption(it.id, it.name) })
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    memoryEnabled.value = WeAgentSettings.memoryEnabled()
                    sendWhileRunningMode.value = WeAgentSettings.sendWhileRunningMode()
                }

                // Observe external service keys and keep API-key-gated tool visibility in sync.
                // Exa / Brave tools are only advertised to the model when the corresponding key
                // is present, so they never produce a "key not configured" error mid-turn.
                launch {
                    WeAgentRepository.observeExternalServices().collectLatest { services ->
                        val keys = services.associateBy { it.serviceId }
                        BuiltinToolProvider.exaKeyPresent =
                            !keys[ExternalServiceId.EXA]?.apiKey.isNullOrBlank()
                        BuiltinToolProvider.braveKeyPresent =
                            !keys[ExternalServiceId.BRAVE]?.apiKey.isNullOrBlank()
                    }
                }

                WeLogger.i(TAG, "WeAgentService initialized")
                // Start the workflow trigger runtime (message/schedule event triggers).
                dev.ujhhgtg.wekit.workflow.trigger.WorkflowRuntime.start()
            }.onFailure { WeLogger.e(TAG, "init failed", it) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Session management (called by the overlay drawer)
    // -----------------------------------------------------------------------------------------

    fun newSession() = scope.launch { createAndSwitchSession() }

    /**
     * Creates a new session (applying the configured defaults), selects it, and returns its id — or
     * null when no model is configured. Unlike [newSession] (fire-and-forget), this completes the
     * create + select inline within the caller's coroutine, so callers can use the returned id
     * immediately without racing session selection.
     */
    private suspend fun createAndSwitchSession(): String? {
        // Guard: refuse to create when no model exists at all, so the user gets a clear message.
        if (WeAgentSettings.defaultModelId() ?: firstAvailableModelId() == null) {
            WeLogger.w(TAG, "cannot create session: no model configured")
            return null
        }
        // Leave model / system prompt / workspace all unbound (null = "默认"): each resolves to the
        // settings default at turn time, so changing a default takes effect on existing sessions too.
        val id = WeAgentRepository.createSession(
            modelId = null,
            systemPromptId = null,
            workspaceId = null,
        )
        switchSessionInternal(id)
        return id
    }

    /**
     * Creates a fresh session using the new-session defaults but does NOT switch the foreground to it
     * — used by GLOBAL triggers, which always run in a brand-new background session. Returns the id,
     * or null if no model is configured. [dev.ujhhgtg.wekit.agent.trigger.TriggerManager.TriggerHost] impl.
     */
    suspend fun createBackgroundSession(): String? {
        // Guard: refuse when no model exists at all (the fire is skipped upstream).
        if (WeAgentSettings.defaultModelId() ?: firstAvailableModelId() == null) {
            WeLogger.w(TAG, "cannot create background session: no model configured")
            return null
        }
        // Unbound model / system prompt / workspace (null = "默认"), resolved at turn time.
        return WeAgentRepository.createSession(
            modelId = null,
            systemPromptId = null,
            workspaceId = null,
        )
    }

    fun switchSession(id: String) = scope.launch { switchSessionInternal(id) }

    private suspend fun switchSessionInternal(id: String) {
        currentSessionId.value = id
        val session = WeAgentRepository.getSession(id)
        withContext(Dispatchers.Main) {
            currentModelId.value = session?.modelId
            currentSystemPromptId.value = session?.systemPromptId
            currentWorkspaceId.value = session?.workspaceId
            // Restore the last persisted usage + context window so the strip survives a session
            // switch / WeChat restart (the next turn's resolveTurnConfig refreshes contextWindow).
            currentUsage.value = session?.let {
                if (it.promptTokens == null && it.completionTokens == null && it.totalTokens == null) null
                else dev.ujhhgtg.wekit.agent.model.LlmUsage(it.promptTokens, it.completionTokens, it.totalTokens)
            }
            currentContextWindow.value = session?.contextWindow
            syncForeground(id)
        }
        reloadMessages(id)
    }

    /**
     * Reconciles the foreground-facing state ([pendingApproval] card + [ballState]) with the newly
     * shown session [id]: surfaces that session's parked approval (if any), and reflects whether the
     * session is still running / awaiting approval / idle. Must run on Main.
     */
    private fun syncForeground(id: String) {
        val parked = pendingApprovals[id]
        pendingApproval.value = parked
        ballState.value = when {
            parked != null -> BallState.PENDING_APPROVAL
            runningTurns[id]?.isActive == true -> BallState.RUNNING
            else -> BallState.IDLE
        }
    }

    /** Toggles a session's favorite (starred) flag. Favorited sessions pin to the top and can't be deleted. */
    fun toggleFavorite(id: String) = scope.launch {
        val session = WeAgentRepository.getSession(id) ?: return@launch
        WeAgentRepository.setFavorite(id, !session.favorite)
    }

    fun deleteSession(id: String) = scope.launch {
        // Favorited sessions are delete-protected (guards trigger-owning sessions). The UI hides the
        // delete affordance for them, but guard here too so no caller can bypass it.
        if (WeAgentRepository.getSession(id)?.favorite == true) {
            WeLogger.w(TAG, "refusing to delete favorited session $id")
            return@launch
        }
        // Cancel any in-flight turn for this session (foreground or background trigger-fired).
        runningTurns.remove(id)?.cancel()
        // Reject any parked approval belonging to the deleted session (foreground or background).
        pendingApprovals.remove(id)?.let { p ->
            if (!p.deferred.isCompleted) p.deferred.complete(ManualApprovalResult.Rejected("会话已删除"))
        }
        WeAgentRepository.deleteSession(id)
        if (currentSessionId.value == id) {
            currentSessionId.value = null
            withContext(Dispatchers.Main) {
                uiMessages.clear()
                currentUsage.value = null
                currentContextWindow.value = null
                currentModelId.value = null
                currentSystemPromptId.value = null
                currentWorkspaceId.value = null
                pendingApproval.value = null
            }
            // Clear the queued message — it belongs to the deleted session.
            queuedMessage.value = null
        }
    }

    fun renameSession(id: String, title: String) = scope.launch {
        WeAgentRepository.renameSession(id, title)
    }

    /**
     * Permanently deletes all messages after [messageTimestamp] in [sessionId] (回到此处). Cancels
     * any running turn for that session first (callers must guard against calling this while a turn
     * is in progress; the guard lives in the UI, but we cancel defensively here too).
     */
    fun truncateToMessage(sessionId: String, messageTimestamp: java.time.Instant) = scope.launch {
        runningTurns[sessionId]?.cancel()
        WeAgentRepository.truncateToMessage(sessionId, messageTimestamp)
        if (sessionId == currentSessionId.value) reloadMessages(sessionId)
    }

    /**
     * Creates a branch of [sourceSessionId] from its beginning up to and including the message at
     * [messageTimestamp], then immediately switches the foreground to the new session. The branch
     * title is "[分支] <original title>". Session metadata (model, system prompt, workspace,
     * favorite) is copied; token usage and triggers are not.
     */
    fun branchSession(sourceSessionId: String, messageTimestamp: java.time.Instant) = scope.launch {
        val newSessionId = WeAgentRepository.branchSession(sourceSessionId, messageTimestamp)
        switchSessionInternal(newSessionId)
    }

    /** Binds (or clears, modelId=null → "默认") the current session's model. */
    fun setSessionModel(modelId: String?) = scope.launch {
        currentSessionId.value?.let { WeAgentRepository.updateSessionModel(it, modelId) }
        withContext(Dispatchers.Main) { currentModelId.value = modelId }
    }

    fun setSessionSystemPrompt(systemPromptId: String?) = scope.launch {
        currentSessionId.value?.let { WeAgentRepository.updateSessionSystemPrompt(it, systemPromptId) }
        withContext(Dispatchers.Main) { currentSystemPromptId.value = systemPromptId }
    }

    /** Binds (or clears, id=null) the current session's workspace. */
    fun setSessionWorkspace(workspaceId: String?) = scope.launch {
        currentSessionId.value?.let { WeAgentRepository.updateSessionWorkspace(it, workspaceId) }
        withContext(Dispatchers.Main) { currentWorkspaceId.value = workspaceId }
    }

    /** Toggles the global memory-enabled setting (also flips fs-tool visibility). */
    fun setMemoryEnabled(enabled: Boolean) = scope.launch {
        WeAgentSettings.set(WeAgentSettings.KEY_MEMORY_ENABLED, enabled.toString())
        BuiltinToolProvider.fsToolsVisible = enabled || WeAgentSettings.workspaceEnabled()
        withContext(Dispatchers.Main) { memoryEnabled.value = enabled }
    }

    private suspend fun reloadMessages(sessionId: String) {
        val rows = mutableListOf<ChatRow>()
        for (m in WeAgentRepository.getMessages(sessionId)) {
            when (m.role) {
                MessageRole.USER -> rows += ChatRow(m.id, ChatRow.Role.USER, m.content, createdAt = m.createdAt)
                MessageRole.ASSISTANT ->
                    // Restore the persisted reasoning ("思考过程") so it survives a reload, matching
                    // the live view built from ReasoningDelta events.
                    rows += ChatRow(m.id, ChatRow.Role.ASSISTANT, m.content, reasoning = m.reasoning, createdAt = m.createdAt)

                MessageRole.TOOL -> {
                    // The TOOL message id is "tool_<callId>" (assigned at write time — reliable, unlike
                    // parsing the content). Look the tool_calls row up by that callId to recover the
                    // tool NAME + approval status, so the card shows "get-contacts · AUTO_ALLOWED"
                    // after a restart instead of degrading to a bare "tool".
                    val callId = m.id.removePrefix("tool_")
                    val tc = WeAgentRepository.getToolCall(callId)
                    // Prefer the persisted result from tool_calls; fall back to the message content
                    // (format "<callId> <resultText>") for rows written before that was stored.
                    val payload = tc?.resultJson ?: m.content.substringAfter(' ', m.content)
                    rows += ChatRow(
                        id = m.id,
                        role = ChatRow.Role.TOOL,
                        text = payload,
                        toolName = tc?.toolName,
                        toolStatus = tc?.approvalStatus,
                        // argumentsJson is always persisted in ToolCallEntity — restore it so the
                        // tool card can show both input and output after a reload/restart.
                        toolInput = tc?.argumentsJson,
                        createdAt = m.createdAt,
                    )
                }

                MessageRole.SYSTEM -> Unit
            }
        }
        withContext(Dispatchers.Main) {
            uiMessages.clear()
            uiMessages.addAll(rows)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Sending a message / running a turn
    // -----------------------------------------------------------------------------------------

    /** Resolves the manual-approval card currently shown (which always belongs to the foreground session). */
    fun resolveApproval(result: ManualApprovalResult) {
        val sid = currentSessionId.value ?: return
        val p = pendingApprovals.remove(sid) ?: pendingApproval.value ?: return
        p.deferred.complete(result)
        pendingApproval.value = null
        if (ballState.value == BallState.PENDING_APPROVAL) {
            ballState.value = if (runningTurns[sid]?.isActive == true) BallState.RUNNING else BallState.IDLE
        }
    }

    /** Cancels the current session's in-flight turn (if any) and clears any queued message. */
    fun cancelTurn() {
        val sid = currentSessionId.value
        if (sid != null) runningTurns.remove(sid)?.cancel()
        ballState.value = BallState.IDLE
        queuedMessage.value = null
        // Clear the approval card — cancelling a turn while in PENDING_APPROVAL would otherwise
        // leave a defunct approval card visible (manualApprovalHandler's finally only removes from
        // the backing map, not from the UI state).
        pendingApproval.value = null
    }

    /** Cancels only the queued (not-yet-sent) message, restoring the input bar. */
    fun cancelQueuedMessage() {
        queuedMessage.value = null
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        // Read once to avoid a race where currentSessionId changes between two separate reads.
        val sessionId = currentSessionId.value

        // If the CURRENT session's turn is already running and there's no queued message yet, queue
        // according to mode. (Other sessions may run concurrently — the queue is a foreground concept.)
        if (sessionId != null && runningTurns[sessionId]?.isActive == true && queuedMessage.value == null) {
            queuedMessage.value = userText
            return
        }

        if (sessionId == null) {
            // Auto-create a session, then send in the SAME coroutine using the returned id (no race
            // on currentSessionId being set by a separate switchSession launch).
            scope.launch {
                val newId = createAndSwitchSession()
                if (newId != null) runTurn(newId, userText)
                else appendSystemNote("未配置可用的模型，请先在设置中添加模型提供方与模型。")
            }
            return
        }
        scope.launch { runTurn(sessionId, userText) }
    }

    private suspend fun runTurn(sessionId: String, userText: String) {
        // Interactive send. The send path already queues when this session is busy, but guard anyway.
        if (runningTurns[sessionId]?.isActive == true) return

        // Optimistically render the user message (this session is the foreground one on a send).
        appendUiRow(ChatRow(id = "u_${System.nanoTime()}", role = ChatRow.Role.USER, text = userText))

        // Wire the steer-hook so that a queued message in QUEUE_AS_STEER mode is injected before
        // the next model request (atomic — consumed once).
        val onFetchSteer: (() -> String?)? = if (sendWhileRunningMode.value == SendWhileRunningMode.QUEUE_AS_STEER) ({
            val msg = queuedMessage.value
            if (msg != null) {
                appendUiRow(
                    ChatRow(
                        id = "u_steer_${System.nanoTime()}",
                        role = ChatRow.Role.USER,
                        text = "(引导) $msg",
                    )
                )
                queuedMessage.value = null
            }
            msg
        }) else null

        launchTurn(sessionId, userText, onFetchSteer, interactive = true)
    }

    /**
     * Runs a turn fired by a workflow (§ workflow). [injectedText] is the serialized event timeline +
     * the trigger's prompt template. Unlike [runTurn] it is NOT tied to the foreground: UI state is
     * only mutated when [sessionId] happens to be the session the user is currently viewing (handled
     * inside [handleEvent] / the append helpers). Skipped if that session already has a running turn,
     * so a burst of trigger fires can't stack turns on one session.
     */
    suspend fun runTriggeredTurn(sessionId: String, injectedText: String) {
        if (runningTurns[sessionId]?.isActive == true) {
            WeLogger.i(TAG, "triggered turn skipped: session $sessionId already running")
            return
        }
        launchTurn(sessionId, injectedText, onFetchSteer = null, interactive = false)
    }

    /**
     * Shared turn launcher for both interactive sends and triggered runs. Builds the engine + VFS for
     * [sessionId], installs the per-turn coroutine contexts (VFS / image sink / session id), collects
     * the engine's event stream, and tracks the job in [runningTurns] keyed by session so multiple
     * sessions can run concurrently. [interactive] gates title generation + queued-message dequeue
     * (only meaningful for foreground sends).
     */
    private suspend fun launchTurn(
        sessionId: String,
        userText: String,
        onFetchSteer: (() -> String?)?,
        interactive: Boolean,
    ) {
        val config = resolveTurnConfig(sessionId)
        if (config == null) {
            if (interactive || sessionId == currentSessionId.value) {
                appendSystemNote("未配置可用的模型，请先在设置中添加模型提供方与模型。")
            }
            return
        }
        val session = WeAgentRepository.getSession(sessionId) ?: return
        // Remove any trailing incomplete assistant turns (thinking-only rows or tool calls whose
        // results never arrived) so the history we send to the API is always well-formed.
        val sanitized = WeAgentRepository.sanitizeSessionHistory(sessionId)
        if (sanitized && sessionId == currentSessionId.value) reloadMessages(sessionId)
        val priorHistory = WeAgentRepository.loadHistory(sessionId)
        val engine = buildEngine(sessionId, session.createdAt)
        // workspaceId semantics: null = "默认" (resolve to the settings default so changing it applies
        // to existing sessions too), "" = "无" (explicitly no workspace), any other value = that workspace.
        val effectiveWorkspaceId = when (val ws = session.workspaceId) {
            null -> WeAgentSettings.defaultWorkspaceId()
            "" -> null
            else -> ws
        }
        val vfs = WorkspaceStore.buildVfs(
            workspaceName = effectiveWorkspaceId?.let { WeAgentRepository.getWorkspaceName(it) },
            memoryEnabled = WeAgentSettings.memoryEnabled(),
        )

        if (sessionId == currentSessionId.value) ballState.value = BallState.RUNNING
        val job = scope.launch {
            try {
                // Install VFS (fs tools), UiImageSink (ui-screenshot), and AgentSessionContext (so the
                // trigger tools know which session "this session" refers to for SESSION-scoped triggers).
                withContext(VfsContext(vfs) + UiImageSink() + AgentSessionContext(sessionId)) {
                    engine.runTurn(
                        TurnConfig(
                            client = config.client,
                            modelIdRemote = config.modelIdRemote,
                            reasoningEffort = config.reasoningEffort,
                            customJsonOverride = config.customJsonOverride,
                            maxTokens = config.maxTokens,
                            systemPromptContent = config.systemPromptContent,
                            perTurnPrompts = config.perTurnPrompts,
                            conditionalPrompts = config.conditionalPrompts,
                            toolLoadingMode = config.toolLoadingMode,
                            maxModelRequests = config.maxModelRequests,
                            onFetchSteerMessage = onFetchSteer,
                        ), priorHistory, userText
                    )
                        .collect { ev -> handleEvent(sessionId, ev) }
                }
                if (interactive) {
                    maybeGenerateTitle(sessionId, userText)
                    // QUEUE_AFTER_TURN: auto-send a queued message after the turn completes.
                    maybeDequeueAfterTurn(sessionId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                WeLogger.e(TAG, "turn crashed (session=$sessionId)", e)
                if (sessionId == currentSessionId.value) ballState.value = BallState.ERROR
            } finally {
                runningTurns.remove(sessionId)
                refreshBallStateForForeground()
                // Replace live-streaming rows (createdAt = Instant.EPOCH) with the DB-persisted
                // rows that carry real timestamps. Without this, "回到此处" on the last assistant
                // message passes Instant.EPOCH to truncateToMessage, which then deletes every
                // message in the session (all real timestamps are > EPOCH). Run under
                // NonCancellable so a cancelled turn still gets the reload.
                if (sessionId == currentSessionId.value) {
                    withContext(kotlinx.coroutines.NonCancellable) { reloadMessages(sessionId) }
                }
            }
        }
        runningTurns[sessionId] = job
    }

    /** Re-derives the ball state after a turn ends: RUNNING if the foreground session is still busy. */
    private fun refreshBallStateForForeground() {
        val fg = currentSessionId.value
        if (ballState.value == BallState.RUNNING || ballState.value == BallState.PENDING_APPROVAL) {
            ballState.value = if (fg != null && runningTurns[fg]?.isActive == true) BallState.RUNNING
            else BallState.IDLE
        }
    }

    /** Dequeues and sends the queued message in QUEUE_AFTER_TURN mode once the turn finishes. */
    private suspend fun maybeDequeueAfterTurn(sessionId: String) {
        val msg = queuedMessage.value ?: return
        if (sendWhileRunningMode.value != SendWhileRunningMode.QUEUE_AFTER_TURN) return
        queuedMessage.value = null
        runTurn(sessionId, msg)
    }

    /**
     * Applies an engine event to UI state. Chat-content mutations (bubbles, tool rows, usage) only
     * apply when [sessionId] is the session currently shown in the panel — background triggered turns
     * on other sessions still persist to Room via the HistorySink, but don't disturb the foreground
     * view. Ball state reflects only the foreground session.
     */
    private suspend fun handleEvent(sessionId: String, ev: AgentEvent) = withContext(Dispatchers.Main) {
        val foreground = sessionId == currentSessionId.value
        when (ev) {
            is AgentEvent.RequestStarted -> {
                // Start a fresh assistant bubble for this round.
                if (foreground) appendUiRow(ChatRow(id = "a_${System.nanoTime()}", role = ChatRow.Role.ASSISTANT, text = ""))
            }

            is AgentEvent.TextDelta -> if (foreground) appendToLastAssistant(text = ev.text)
            is AgentEvent.ReasoningDelta -> if (foreground) appendToLastAssistant(reasoning = ev.text)
            is AgentEvent.ToolCallStarted ->
                if (foreground) {
                    // Tool call means the model is done reasoning — clear the streaming flag on the
                    // last assistant row so ReasoningCard switches to "思考过程" and auto-collapses.
                    appendToLastAssistant(endReasoning = true)
                    appendUiRow(ChatRow(id = "t_${ev.callId}", role = ChatRow.Role.TOOL, text = "", toolName = ev.toolName, toolInput = ev.argumentsJson))
                }

            is AgentEvent.ToolAwaitingApproval -> if (foreground) ballState.value = BallState.PENDING_APPROVAL
            is AgentEvent.ToolCallFinished -> if (foreground) updateToolRow(ev.callId, ev.status, ev.resultText)
            is AgentEvent.UsageUpdated -> {
                // Persist for every session (background turns too) so the strip survives switch/restart;
                // mirror to the live UI state only for the foreground session.
                WeAgentRepository.updateSessionUsage(sessionId, ev.usage)
                if (foreground) currentUsage.value = ev.usage
            }

            is AgentEvent.TurnCompleted -> if (foreground) refreshBallStateForForeground()
            is AgentEvent.MaxRequestsReached -> if (foreground) appendSystemNote("已达到最大调用次数（${ev.cap}）。")
            is AgentEvent.TurnFailed -> {
                if (foreground) {
                    appendSystemNote("出错：${ev.error.message ?: ev.error.javaClass.simpleName}")
                    ballState.value = BallState.ERROR
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Engine assembly
    // -----------------------------------------------------------------------------------------

    private suspend fun buildEngine(sessionId: String, promptAnchorTime: java.time.Instant): AgentSessionEngine {
        val composer = PromptComposer(
            toolLoadingMode = WeAgentSettings.toolLoadingMode(),
            workspaceEnabled = WeAgentSettings.workspaceEnabled(),
            memoryEnabled = WeAgentSettings.memoryEnabled(),
            memoryIndexContent = if (WeAgentSettings.memoryEnabled()) WorkspaceStore.readMemoryIndex() else null,
            skillCatalog = dev.ujhhgtg.wekit.agent.skill.SkillStore.enabledSkills().map { it.name to it.description },
            // Anchor the system-prompt clock to the session's creation time so the cacheable prefix
            // stays byte-stable across every turn (see PromptComposer.promptAnchorTime).
            promptAnchorTime = promptAnchorTime,
        )
        val approval = ApprovalGateway(
            manualHandler = manualApprovalHandler,
            smallModel = resolveSmallModel(sessionId),
        )
        val sink = RoomHistorySink(sessionId)
        return AgentSessionEngine(registry, approval, composer, sink)
    }

    /**
     * Parks a manual-approval request for the tool's session and suspends until it's resolved. The
     * request is keyed by the session id (read from [AgentSessionContext] installed around the turn),
     * so a background session waits indefinitely without blocking or disturbing the foreground —
     * its card only appears once the user switches to that session ([syncForeground]). If for some
     * reason the session id is missing, we fall back to the current session so the card still shows.
     */
    private val manualApprovalHandler = ManualApprovalHandler { pending ->
        val sessionId = currentCoroutineContext()[AgentSessionContext]?.sessionId
            ?: currentSessionId.value
            ?: return@ManualApprovalHandler ManualApprovalResult.Rejected("无法确定审批所属会话")
        val deferred = CompletableDeferred<ManualApprovalResult>()
        val ui = PendingApprovalUi(pending, deferred)
        pendingApprovals[sessionId] = ui
        withContext(Dispatchers.Main) {
            // Only surface the card + flip the ball if this session is the one on screen.
            if (sessionId == currentSessionId.value) {
                pendingApproval.value = ui
                ballState.value = BallState.PENDING_APPROVAL
            }
        }
        try {
            deferred.await()
        } finally {
            // Whether resolved by the user, session switch cleanup, or cancellation — drop it.
            pendingApprovals.remove(sessionId, ui)
        }
    }

    private suspend fun resolveTurnConfig(sessionId: String): TurnConfig? {
        val session = WeAgentRepository.getSession(sessionId) ?: return null
        // null model / system prompt mean "默认": resolve to the live settings default at turn time
        // (same semantics as the workspace), so changing a default applies to existing sessions too.
        val effectiveModelId = session.modelId ?: WeAgentSettings.defaultModelId() ?: firstAvailableModelId()
        val model = effectiveModelId?.let { WeAgentRepository.getModel(it) } ?: return null
        // Persist the resolved model's context window on the session so the usage strip can restore the
        // bar after a switch / restart — even on "默认" (session.modelId == null), where a raw id lookup
        // wouldn't match. Persisted for all sessions; only mirrored to the foreground UI state.
        WeAgentRepository.updateSessionContextWindow(sessionId, model.contextWindow)
        if (sessionId == currentSessionId.value) {
            withContext(Dispatchers.Main) { currentContextWindow.value = model.contextWindow }
        }
        val provider = WeAgentRepository.getDecryptedModelProvider(model.providerId) ?: return null
        val client = runCatching { ModelProviderManager.clientFor(provider) }.getOrNull() ?: return null
        // systemPromptId semantics: null = "默认" (follow settings default), "" = "无" (explicitly none),
        // any other value = that specific prompt.
        val effectiveSystemPromptId = when (val sp = session.systemPromptId) {
            null -> WeAgentSettings.defaultSystemPromptId()
            "" -> null
            else -> sp
        }
        val systemPromptContent = WeAgentRepository.getSystemPromptContent(effectiveSystemPromptId)
        val perTurn = WeAgentRepository.getEnabledPerTurnPrompts().map { it.content }
        val conditionals = WeAgentRepository.getEnabledConditionalPrompts()
        val req = ModelProviderManager.buildRequest(model, emptyList(), emptyList())
        // Gate ui-screenshot based on whether the session model declares vision support.
        BuiltinToolProvider.visionToolsVisible = model.supportsVision
        return TurnConfig(
            client = client,
            modelIdRemote = model.modelIdRemote,
            reasoningEffort = req.reasoningEffort,
            customJsonOverride = req.customJsonOverride,
            maxTokens = req.maxTokens,
            systemPromptContent = systemPromptContent,
            perTurnPrompts = perTurn,
            conditionalPrompts = conditionals,
            toolLoadingMode = WeAgentSettings.toolLoadingMode(),
            maxModelRequests = WeAgentSettings.maxModelRequests(),
        )
    }

    /**
     * Resolves the small model for smart-approval/title generation (§5.4). When no dedicated small
     * model is configured ("与主模型相同"), falls back to the session's own main model so
     * smart-approval and title generation still work instead of failing as "unconfigured".
     */
    private suspend fun resolveSmallModel(sessionId: String): SmallModelRef? {
        val modelId = WeAgentSettings.smallModelId()
            ?: WeAgentRepository.getSession(sessionId)?.modelId
            ?: WeAgentSettings.defaultModelId()
            ?: firstAvailableModelId()
            ?: return null
        val model = WeAgentRepository.getModel(modelId) ?: return null
        val provider = WeAgentRepository.getDecryptedModelProvider(model.providerId) ?: return null
        val client = runCatching { ModelProviderManager.clientFor(provider) }.getOrNull() ?: return null
        return SmallModelRef(client, model.modelIdRemote, model.reasoningEffort, model.maxTokens)
    }

    private suspend fun firstAvailableModelId(): String? =
        WeAgentRepository.firstModelId()

    /** Generates a session title from the first user message if it's still the placeholder. */
    private suspend fun maybeGenerateTitle(sessionId: String, firstUserText: String) {
        val session = WeAgentRepository.getSession(sessionId) ?: return
        if (session.title != "新对话") return
        val small = resolveSmallModel(sessionId)
        val title = if (small != null) {
            runCatching { TitleGenerator.generate(small, firstUserText) }.getOrNull()
        } else null
        WeAgentRepository.renameSession(sessionId, title ?: firstUserText.take(10))
    }

    // -----------------------------------------------------------------------------------------
    // Room-backed HistorySink
    // -----------------------------------------------------------------------------------------

    private class RoomHistorySink(private val sessionId: String) : AgentSessionEngine.HistorySink {
        override suspend fun onUserMessage(content: String) {
            WeAgentRepository.appendUserMessage(sessionId, content)
        }

        override suspend fun onAssistantMessage(content: String?, reasoning: String?, reasoningSignature: String?, toolCalls: List<LlmToolCall>) {
            WeAgentRepository.appendAssistantMessage(sessionId, content, reasoning, reasoningSignature, toolCalls)
        }

        override suspend fun onToolResult(
            callId: String,
            toolName: String,
            providerId: String,
            argumentsJson: String,
            resultText: String,
            status: ApprovalStatus
        ) {
            WeAgentRepository.appendToolResult(sessionId, callId, toolName, providerId, argumentsJson, resultText, status)
        }
    }

    // -----------------------------------------------------------------------------------------
    // UI-state mutation helpers (must run on Main)
    // -----------------------------------------------------------------------------------------

    private fun appendUiRow(row: ChatRow) {
        uiMessages.add(row)
    }

    private fun appendToLastAssistant(text: String? = null, reasoning: String? = null, endReasoning: Boolean = false) {
        val idx = uiMessages.indexOfLast { it.role == ChatRow.Role.ASSISTANT }
        if (idx < 0) return
        val cur = uiMessages[idx]
        uiMessages[idx] = cur.copy(
            text = cur.text + (text ?: ""),
            reasoning = if (reasoning != null) (cur.reasoning ?: "") + reasoning else cur.reasoning,
            // reasoningStreaming lifecycle:
            //   reasoning != null       → set true (first/continuing reasoning delta)
            //   text != null / tool call started / endReasoning → set false (reasoning phase over)
            //   all null, no endReasoning → preserve (shouldn't happen in practice)
            reasoningStreaming = when {
                reasoning != null -> true
                text != null || endReasoning -> false
                else -> cur.reasoningStreaming
            },
        )
    }

    private fun updateToolRow(callId: String, status: ApprovalStatus, resultText: String) {
        val idx = uiMessages.indexOfLast { it.id == "t_$callId" }
        if (idx < 0) return
        uiMessages[idx] = uiMessages[idx].copy(text = resultText, toolStatus = status)
    }

    private fun appendSystemNote(text: String) {
        uiMessages.add(ChatRow(id = "s_${System.nanoTime()}", role = ChatRow.Role.SYSTEM_NOTE, text = text))
    }
}
