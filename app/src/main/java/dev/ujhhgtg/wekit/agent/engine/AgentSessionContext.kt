package dev.ujhhgtg.wekit.agent.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the id of the session a turn is running in, as a coroutine-context element (mirrors
 * [dev.ujhhgtg.wekit.agent.workspace.VfsContext] and [dev.ujhhgtg.wekit.agent.ui.UiImageSink]).
 *
 * The tool-invoker layer has no session parameter, so trigger tools (which need to know "which
 * session am I in" to create a SESSION-scoped trigger by default) read it from the current coroutine
 * context. Installed by WeAgentService around both `runTurn` and `runTriggeredTurn`.
 */
class AgentSessionContext(val sessionId: String) : AbstractCoroutineContextElement(AgentSessionContext) {
    companion object Key : CoroutineContext.Key<AgentSessionContext>
}
