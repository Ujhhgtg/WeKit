package dev.ujhhgtg.wekit.python.runtime

import dev.ujhhgtg.wekit.python.api.PythonPluginHost
import dev.ujhhgtg.wekit.python.api.PythonPluginRequest
import dev.ujhhgtg.wekit.python.api.PythonRuntimeApi
import dev.ujhhgtg.wekit.python.api.PythonRuntimeBackend
import dev.ujhhgtg.wekit.python.api.PythonRuntimeConfig

/** Loader-neutral placeholder; native/backend implementation belongs to later tasks. */
object RuntimeEntrypoint {
    @JvmStatic
    fun bootstrap(apiVersion: Int, config: PythonRuntimeConfig, host: PythonPluginHost): PythonRuntimeBackend {
        require(apiVersion == PythonRuntimeApi.API_VERSION) { "Unsupported Python runtime API version: $apiVersion" }
        requireNotNull(config) { "Runtime config is required" }
        requireNotNull(host) { "Runtime host is required" }
        return UnavailableBackend()
    }

    private class UnavailableBackend : PythonRuntimeBackend {
        private fun unavailable() = UnsupportedOperationException(
            "Python runtime backend is not implemented in Task 1; native bootstrap was not attempted",
        )
        override fun start(config: PythonRuntimeConfig): Unit = throw unavailable()
        override fun activatePlugin(request: PythonPluginRequest, host: PythonPluginHost): Unit = throw unavailable()
        override fun deactivatePlugin(pluginId: String): Unit = throw unavailable()
        override fun reloadPlugin(request: PythonPluginRequest, host: PythonPluginHost): Unit = throw unavailable()
    }
}
