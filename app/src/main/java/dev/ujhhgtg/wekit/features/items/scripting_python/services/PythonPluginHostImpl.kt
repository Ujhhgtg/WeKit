package dev.ujhhgtg.wekit.features.items.scripting_python.services

import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginScope
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonCrashGuard
import dev.ujhhgtg.wekit.python.api.PythonDexHost
import dev.ujhhgtg.wekit.python.api.PythonHookHost
import dev.ujhhgtg.wekit.python.api.PythonLogger
import dev.ujhhgtg.wekit.python.api.PythonPluginHost
import dev.ujhhgtg.wekit.python.api.PythonTaskHost

class PythonPluginHostImpl(
    private val pluginId: String,
    private val scope: PythonPluginScope,
) : PythonPluginHost {
    private val logger = PythonLogHostImpl(pluginId)
    private val hooks = PythonHookHostImpl(pluginId, scope)
    private val dex = PythonDexHostImpl(scope)
    private val tasks = PythonTaskHostImpl(scope)

    override fun logger(pluginId: String): PythonLogger = checked(pluginId, logger)
    override fun hooks(pluginId: String): PythonHookHost = checked(pluginId, hooks)
    override fun dex(pluginId: String): PythonDexHost = checked(pluginId, dex)
    override fun tasks(pluginId: String): PythonTaskHost = checked(pluginId, tasks)

    override fun beginExecution(pluginId: String, phase: String): Long {
        checked(pluginId, Unit)
        return PythonCrashGuard.begin(pluginId, phase)
    }

    override fun finishExecution(pluginId: String, token: Long) {
        require(pluginId == this.pluginId) { "Host service belongs to ${this.pluginId}, not $pluginId" }
        PythonCrashGuard.finish(token)
    }

    private fun <T> checked(requestedId: String, service: T): T {
        require(requestedId == pluginId) { "Host service belongs to $pluginId, not $requestedId" }
        check(!scope.isClosed) { "Python plugin scope is closed: $pluginId" }
        return service
    }
}
