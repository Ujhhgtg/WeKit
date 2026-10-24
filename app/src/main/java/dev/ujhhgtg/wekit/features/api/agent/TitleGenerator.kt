package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.engine.SmallModelRef
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRequest
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import kotlinx.coroutines.flow.toList

/**
 * Generates a short conversation title from the first user message using the small model (§5.4).
 * This is an independent one-shot request that does not include conversation history. Callers fall back to
 * truncating the user's first message on failure.
 */
object TitleGenerator {

    suspend fun generate(small: SmallModelRef, sessionId: String, firstUserText: String): String? {
        val prompt = "为下面这段用户消息生成一个简短的 LLM 对话标题（不超过 12 个字，直接输出标题本身，不要引号或其他说明）：\n\n$firstUserText"
        val request = LlmRequest(
            modelIdRemote = small.modelIdRemote,
            messages = listOf(LlmMessage(role = LlmRole.USER, content = prompt)),
            sessionId = sessionId,
            tools = emptyList(),
            reasoningEffort = small.reasoningEffort,
            customJsonOverride = null,
            maxTokens = small.maxTokens,
            stream = false,
        )
        val events = small.client.stream(request).toList()
        val text = (events.lastOrNull { it is LlmStreamEvent.Completed } as? LlmStreamEvent.Completed)
            ?.message?.content?.trim()
        return text?.takeIf { it.isNotEmpty() }?.take(20)
    }
}
