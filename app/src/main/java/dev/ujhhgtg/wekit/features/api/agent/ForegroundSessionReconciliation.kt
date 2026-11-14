package dev.ujhhgtg.wekit.features.api.agent

suspend fun <S, M> reconcileForegroundSession(
    currentSessionId: suspend () -> String?,
    loadState: suspend (String) -> S?,
    applyStateIfCurrent: suspend (String, S) -> Boolean,
    loadMessages: suspend (String) -> M,
    publishMessagesIfCurrent: suspend (String, M) -> Boolean,
) {
    while (true) {
        val sessionId = currentSessionId() ?: return
        val state = loadState(sessionId)
        if (state == null) {
            if (currentSessionId() == sessionId) return
            continue
        }
        if (!applyStateIfCurrent(sessionId, state)) continue
        val messages = loadMessages(sessionId)
        if (!publishMessagesIfCurrent(sessionId, messages)) continue
        if (currentSessionId() == sessionId) return
    }
}
