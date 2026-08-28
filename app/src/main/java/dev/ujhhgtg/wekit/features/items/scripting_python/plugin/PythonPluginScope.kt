package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

class PythonPluginCleanupException(val errors: List<Throwable>) :
    IllegalStateException("${errors.size} Python plugin cleanup action(s) failed", errors.first())

class PythonPluginScope(val pluginId: String) {
    private val lock = Any()
    private val cleanup = mutableListOf<() -> Unit>()
    private val references = mutableListOf<Any>()

    @Volatile
    var isClosed = false
        private set

    fun defer(action: () -> Unit) = synchronized(lock) {
        check(!isClosed) { "Python plugin scope is closed: $pluginId" }
        cleanup += action
    }

    fun trackReference(reference: Any) = synchronized(lock) {
        check(!isClosed) { "Python plugin scope is closed: $pluginId" }
        references += reference
    }

    fun track(reference: Any, action: () -> Unit) = synchronized(lock) {
        check(!isClosed) { "Python plugin scope is closed: $pluginId" }
        references += reference
        cleanup += action
    }

    fun close(): List<Throwable> {
        val actions = synchronized(lock) {
            if (isClosed) return emptyList()
            isClosed = true
            cleanup.asReversed().toList().also { cleanup.clear() }
        }
        val errors = actions.mapNotNull { action -> runCatching(action).exceptionOrNull() }
        synchronized(lock) { references.clear() }
        return errors
    }
}
