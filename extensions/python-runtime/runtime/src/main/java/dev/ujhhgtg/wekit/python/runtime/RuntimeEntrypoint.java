package dev.ujhhgtg.wekit.python.runtime;

import dev.ujhhgtg.wekit.python.api.PythonPluginHost;
import dev.ujhhgtg.wekit.python.api.PythonPluginRequest;
import dev.ujhhgtg.wekit.python.api.PythonRuntimeApi;
import dev.ujhhgtg.wekit.python.api.PythonRuntimeBackend;
import dev.ujhhgtg.wekit.python.api.PythonRuntimeConfig;

/**
 * Loader-neutral placeholder for the runtime pack bootstrap.
 *
 * Native loading and the backend are intentionally left for the runtime
 * implementation task. Keeping this class free of implementation references is
 * required so reflection can verify the contract before any native load.
 */
public final class RuntimeEntrypoint {
    private RuntimeEntrypoint() { }

    public static PythonRuntimeBackend bootstrap(
            int apiVersion,
            PythonRuntimeConfig config,
            PythonPluginHost host) {
        if (apiVersion != PythonRuntimeApi.API_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Python runtime API version: " + apiVersion);
        }
        if (config == null || host == null) {
            throw new IllegalArgumentException("Runtime config and host are required");
        }
        return new UnavailableBackend();
    }

    private static final class UnavailableBackend implements PythonRuntimeBackend {
        private UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException(
                    "Python runtime backend is not implemented in Task 1; native bootstrap was not attempted");
        }

        @Override public void start(PythonRuntimeConfig config) { throw unavailable(); }
        @Override public void activatePlugin(PythonPluginRequest request, PythonPluginHost host) { throw unavailable(); }
        @Override public void deactivatePlugin(String pluginId) { throw unavailable(); }
        @Override public void reloadPlugin(PythonPluginRequest request, PythonPluginHost host) { throw unavailable(); }
    }
}
