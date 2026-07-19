package dev.ujhhgtg.wekit.agent.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the id of the session a turn is running in, as a coroutine-context element (mirrors
 * [dev.ujhhgtg.wekit.agent.workspace.VfsContext] and [dev.ujhhgtg.wekit.agent.ui.UiImageSink]).
 *
 * The tool-invoker layer has no session parameter, so tools that need to know which session they
 * are executing in read it from the current coroutine context. Installed by WeAgentService around
 * `runTurn`.
 */
class AgentSessionContext(val sessionId: String) : AbstractCoroutineContextElement(AgentSessionContext) {
    companion object Key : CoroutineContext.Key<AgentSessionContext>
}
