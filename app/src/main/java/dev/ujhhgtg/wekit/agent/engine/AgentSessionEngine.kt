package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.model.LlmClient
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.LlmToolCall
import dev.ujhhgtg.wekit.agent.model.LlmToolSpec
import dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.WireTool
import dev.ujhhgtg.wekit.agent.ui.UiImageSink
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Everything the engine needs to run one turn, resolved by the caller (WeAgentService) from the
 * session's model + the current prompt/settings state.
 */
class TurnConfig(
    val client: LlmClient,
    val modelIdRemote: String,
    val reasoningEffort: String?,
    val customJsonOverride: JsonObject?,
    /** The session's bound system prompt content, or null. */
    val systemPromptContent: String?,
    /** Globally-enabled per-turn prompt contents (prepended to each user message). */
    val perTurnPrompts: List<String>,
    /** Globally-enabled conditional prompts (regex-matched against each reply). */
    val conditionalPrompts: List<ConditionalPromptEntity>,
    val toolLoadingMode: ToolLoadingMode,
    /** Per-model max output tokens, or null to omit the field (provider default). */
    val maxTokens: Int? = null,
    /**
     * Which conditionally-advertised builtin tools this turn may see (vision / fs). Snapshotted per
     * turn — never read from a process-global flag — so a background trigger-fired turn on a
     * non-vision model can't strip `ui-screenshot` out from under a concurrently-running vision turn
     * (or hand it to a model whose provider then rejects the injected images).
     */
    val toolVisibility: dev.ujhhgtg.wekit.agent.tool.ToolVisibility =
        dev.ujhhgtg.wekit.agent.tool.ToolVisibility.fromGlobals(),
    /**
     * Queue-after-turn steer-hook: called by the engine at the top of every while-loop iteration. If
     * non-null and returning a non-blank string, the engine injects it as a transient USER message
     * before the next model request (not persisted). The callback should consume the message
     * atomically so each steer fires once.
     */
    val onFetchSteerMessage: (() -> String?)? = null,
)

/**
 * The Agent Loop (§2). Given the prior conversation and a new user message, it repeatedly calls the
 * model, executes any tool calls it returns (gated by [ApprovalGateway] and persisted via
 * [historySink]), feeds results back, and loops until the model returns no tool call or an error
 * occurs.
 *
 * The loop is transport-agnostic: it works in provider-neutral [LlmMessage] space and delegates
 * wire translation to the [LlmClient]. It emits [AgentEvent]s for the UI as a cold [Flow]; cancel
 * the collecting coroutine to abort the turn.
 */
class AgentSessionEngine(
    private val registry: ToolRegistry,
    private val approvalGateway: ApprovalGateway,
    private val promptComposer: PromptComposer,
    private val historySink: HistorySink,
) {
    /**
     * Persists conversation state as the loop advances. Implemented over Room by WeAgentService;
     * kept as an interface so the engine has no direct DB dependency and stays testable.
     */
    interface HistorySink {
        suspend fun onAssistantMessage(content: String?, reasoning: String?, reasoningSignature: String?, toolCalls: List<LlmToolCall>)
        suspend fun onToolResult(callId: String, toolName: String, providerId: String, argumentsJson: String, resultText: String, status: ApprovalStatus)
        suspend fun onUserMessage(content: String)
    }

    /**
     * Runs one turn. [priorMessages] is the full provider-neutral history (excluding the system
     * message, which is composed here). [userMessage] is the raw new user input.
     *
     * Only the raw [userMessage] is persisted/displayed; the per-turn prompt prefix (§6) is applied
     * transiently to the copy sent to the model, so it never compounds across reloads. The previous
     * assistant reply is NOT re-prepended — it is already present in [priorMessages].
     */
    fun runTurn(
        config: TurnConfig,
        priorMessages: List<LlmMessage>,
        userMessage: String,
    ): Flow<AgentEvent> = channelFlow {
        try {
            val systemMessage = promptComposer.composeSystemMessage(config.systemPromptContent)
            // Persist/display the raw user message; the model gets the per-turn-augmented copy.
            historySink.onUserMessage(userMessage)
            val sentUserText = promptComposer.composeTurnUserMessage(config.perTurnPrompts, userMessage)

            // Working message list for this turn: [system] + prior + this user message.
            val messages = ArrayList<LlmMessage>()
            messages += LlmMessage(role = LlmRole.SYSTEM, content = systemMessage)
            messages += priorMessages
            messages += LlmMessage(role = LlmRole.USER, content = sentUserText)

            // Per-turn dynamic-discovery state (exposed tool names discovered so far).
            val discovered = LinkedHashSet<String>()

            var requestIndex = 0

            while (true) {
                currentCoroutineContext().ensureActive()

                // Steer-hook: inject a transient user message from the queued-mechanism before the
                // next API request (not persisted — purely ephemeral steering input).
                val steerText = config.onFetchSteerMessage?.invoke()?.takeIf { it.isNotBlank() }
                if (steerText != null) {
                    messages += LlmMessage(role = LlmRole.USER, content = steerText)
                    // The raw text isn't persisted to the history (it's steering, not a real user
                    // utterance), but the model will respond to it.
                }

                requestIndex++
                send(AgentEvent.RequestStarted(requestIndex))

                val wireTools = registry.requestTools(config.toolLoadingMode, discovered, config.toolVisibility)
                val request = dev.ujhhgtg.wekit.agent.model.LlmRequest(
                    modelIdRemote = config.modelIdRemote,
                    messages = pruneStaleImages(messages),
                    tools = wireTools.map { it.toSpec() },
                    reasoningEffort = config.reasoningEffort,
                    customJsonOverride = config.customJsonOverride,
                    maxTokens = config.maxTokens,
                    stream = true,
                )

                // Stream one model response.
                val textBuf = StringBuilder()
                val reasoningBuf = StringBuilder()
                var completed: LlmStreamEvent.Completed? = null
                var failure: Throwable? = null

                config.client.stream(request).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.TextDelta -> {
                            textBuf.append(ev.text); send(AgentEvent.TextDelta(ev.text))
                        }

                        is LlmStreamEvent.ReasoningDelta -> {
                            reasoningBuf.append(ev.text); send(AgentEvent.ReasoningDelta(ev.text))
                        }

                        is LlmStreamEvent.Completed -> completed = ev
                        is LlmStreamEvent.Failed -> failure = ev.error
                    }
                }

                val err = failure
                if (err != null) {
                    send(AgentEvent.TurnFailed(err))
                    return@channelFlow
                }
                completed?.usage?.let { send(AgentEvent.UsageUpdated(it)) }
                val assistant = completed?.message
                    ?: LlmMessage(role = LlmRole.ASSISTANT, content = textBuf.toString().ifEmpty { null })

                messages += assistant
                historySink.onAssistantMessage(assistant.content, assistant.reasoning, assistant.reasoningSignature, assistant.toolCalls)

                // No tool calls -> round over, turn ends (§2.1).
                if (assistant.toolCalls.isEmpty()) {
                    // Conditional prompts (§6): if any regex matches, append a system-reminder user
                    // message and continue the loop with another request.
                    val reminders = promptComposer.matchConditionalPrompts(
                        config.conditionalPrompts, assistant.content.orEmpty()
                    )
                    if (reminders.isNotEmpty()) {
                        reminders.forEach { messages += LlmMessage(role = LlmRole.USER, content = it) }
                        continue
                    }
                    send(AgentEvent.TurnCompleted(assistant.content))
                    return@channelFlow
                }

                // Execute each tool call, appending a TOOL message per call.
                for (call in assistant.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    send(AgentEvent.ToolCallStarted(call.id, call.name, call.argumentsJson))
                    val (text, status, providerId) = executeToolCall(call, assistant.content, discovered, config.toolVisibility) { toolName ->
                        send(AgentEvent.ToolAwaitingApproval(call.id, toolName))
                    }
                    messages += LlmMessage(role = LlmRole.TOOL, content = text, toolCallId = call.id)
                    historySink.onToolResult(call.id, call.name, providerId, call.argumentsJson, text, status)
                    send(AgentEvent.ToolCallFinished(call.id, call.name, status, text))
                }

                // Vision: a tool (ui-screenshot) may have staged images into the UiImageSink this
                // round. Inject them as a transient USER message so the model sees them next request.
                // These are intentionally NOT persisted via historySink — screenshots are heavy and
                // only relevant to the live turn (mirrors how Anthropic thinking blocks aren't replayed).
                val stagedImages = currentCoroutineContext()[UiImageSink]?.drain().orEmpty()
                if (stagedImages.isNotEmpty()) {
                    messages += LlmMessage(
                        role = LlmRole.USER,
                        content = "以下是刚才工具捕获的界面截图（${stagedImages.size} 张）：",
                        images = stagedImages,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WeLogger.e(TAG, "turn failed", e)
            send(AgentEvent.TurnFailed(e))
        } finally {
            teardownTurn()
        }
    }

    /**
     * Best-effort cleanup that must run however the turn ended (completion, failure, user cancel).
     *
     * - A gesture left held by `ui-touch-down` would otherwise leave WeChat with a stuck ACTION_DOWN
     *   in its input state until the next `ui-touch-up` that may never come, and pin the Activity.
     * - JVM handles are weakly held, so nothing is pinned; this just reaps the collected shells.
     *   A full clear is intentionally avoided — the registry is process-global while turns are
     *   per-session, and concurrent sessions would lose their live handles.
     */
    private fun teardownTurn() {
        runCatching { dev.ujhhgtg.wekit.agent.ui.UiAutomator.cancelActiveGesture() }
            .onFailure { WeLogger.w(TAG, "gesture teardown failed: ${it.message}") }
        runCatching { dev.ujhhgtg.wekit.agent.jvm.JvmObjectRegistry.purgeDead() }
            .onFailure { WeLogger.w(TAG, "handle purge failed: ${it.message}") }
    }

    /**
     * Context economy: screenshots staged by `ui-screenshot` are appended to the working message list
     * and would otherwise be re-sent on **every** later request of the same turn. In the intended
     * screenshot → tap → screenshot loop that grows quadratically — by round 10 the body also carries
     * the 9 stale screenshots, each a few hundred KB of base64.
     *
     * So only the most recent image message keeps its payload; earlier ones are replaced by a short
     * note. Text (tool results, narration) is never touched — only the image payloads are dropped.
     * Do not "optimise" this away: the model only ever needs to see the current screen.
     */
    private fun pruneStaleImages(messages: List<LlmMessage>): List<LlmMessage> {
        // Fast path: zero or one image message — nothing to prune (still a defensive copy, since the
        // caller keeps mutating its working list after the request is built).
        if (messages.count { it.images.isNotEmpty() } <= 1) return messages.toList()
        val newest = messages.indexOfLast { it.images.isNotEmpty() }
        return messages.mapIndexed { i, m ->
            if (i == newest || m.images.isEmpty()) m
            else m.copy(
                content = "（已省略 ${m.images.size} 张较早的界面截图，只保留最近一次截图以节省上下文）",
                images = emptyList(),
            )
        }
    }

    /**
     * Handles one tool call end-to-end: the discover_tools meta-tool, permission gating, and
     * execution. Returns (resultText, approvalStatus, providerId).
     */
    private suspend fun executeToolCall(
        call: LlmToolCall,
        modelExplanation: String?,
        discovered: MutableSet<String>,
        visibility: dev.ujhhgtg.wekit.agent.tool.ToolVisibility,
        onAwaitingApproval: suspend (String) -> Unit,
    ): Triple<String, ApprovalStatus, String> {
        val args = parseArgs(call.argumentsJson)

        // discover_tools meta-tool (§3.3): handled by the engine, always allowed.
        if (call.name == ToolRegistry.DISCOVER_TOOLS_NAME) {
            val text = ToolDiscovery.handle(registry, args, discovered, visibility)
            return Triple(text, ApprovalStatus.AUTO_ALLOWED, "builtin")
        }

        val tool = registry.findByExposedName(call.name, visibility)
            ?: return Triple("Unknown tool: ${call.name}", ApprovalStatus.AUTO_ALLOWED, "")

        if (tool.mode == dev.ujhhgtg.wekit.agent.tool.ToolMode.MANUAL_APPROVAL) onAwaitingApproval(call.name)

        val decision = approvalGateway.decide(
            mode = tool.mode,
            toolName = tool.exposedName,
            providerName = tool.provider.name,
            argumentsJson = call.argumentsJson,
            modelExplanation = modelExplanation,
        )

        return when (decision) {
            is ApprovalDecision.Allowed -> {
                val status = when (tool.mode) {
                    dev.ujhhgtg.wekit.agent.tool.ToolMode.MANUAL_APPROVAL -> ApprovalStatus.USER_APPROVED
                    dev.ujhhgtg.wekit.agent.tool.ToolMode.SMART_APPROVAL -> ApprovalStatus.AI_APPROVED
                    else -> ApprovalStatus.AUTO_ALLOWED
                }
                val result = runCatching { registry.execute(tool, args) }
                    .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
                Triple(result, status, tool.provider.id)
            }

            is ApprovalDecision.Denied -> {
                val status = if (decision.bySmartReview) ApprovalStatus.AI_REJECTED else ApprovalStatus.USER_REJECTED
                Triple(approvalGateway.deniedResultText(decision), status, tool.provider.id)
            }
        }
    }

    private fun parseArgs(argumentsJson: String): JsonObject =
        runCatching { dev.ujhhgtg.wekit.agent.model.LlmJson.json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun WireTool.toSpec(): LlmToolSpec =
        LlmToolSpec(name = exposedName, description = description, parametersSchema = jsonSchema)

    private companion object {
        const val TAG = "AgentSessionEngine"
    }
}
