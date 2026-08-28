package dev.ujhhgtg.wekit.agent.ui

import dev.ujhhgtg.wekit.agent.model.LlmImage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element that lets a UI tool (notably `ui-screenshot`) stage images captured
 * during a tool call, for the engine to pick up and inject as a transient USER message right after
 * the tool result.
 *
 * Why a side-channel and not the tool's return string: tool results are text-only in every provider
 * wire format, and OpenAI/Anthropic reject images inside a `tool`/`tool_result` message. So the
 * screenshot tool returns a short text acknowledgement AND stages the raw image here; the engine
 * drains the staged images after each tool call and appends them as a normal `user` message (a valid
 * position for image content in all three APIs).
 *
 * Installed by [dev.ujhhgtg.wekit.features.api.agent.WeAgentService] around
 * [dev.ujhhgtg.wekit.agent.engine.AgentSessionEngine.runTurn]. Screenshots are intentionally NOT persisted to
 * Room — they live only for the current turn (context economy; a stale screenshot is worse than
 * none).
 */
class UiImageSink : AbstractCoroutineContextElement(UiImageSink) {
    companion object Key : CoroutineContext.Key<UiImageSink>

    private val staged = CopyOnWriteArrayList<LlmImage>()

    /** Stage one captured image for the engine to inject after the current tool call. */
    fun stage(image: LlmImage) {
        staged.add(image)
    }

    /** Remove and return everything staged so far (called by the engine after each tool call). */
    fun drain(): List<LlmImage> {
        if (staged.isEmpty()) return emptyList()
        val snapshot = staged.toList()
        staged.clear()
        return snapshot
    }
}
