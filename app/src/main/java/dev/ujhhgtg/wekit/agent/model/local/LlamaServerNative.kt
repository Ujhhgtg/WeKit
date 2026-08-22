package dev.ujhhgtg.wekit.agent.model.local

/**
 * JNI bridge to the wekit-llama native controller shipped in the llama-native
 * extension pack. The library must be System.load-ed (via
 * `NativeLoader.ensureLlamaLoaded`) before any call. All methods return the
 * controller's lifecycle status JSON:
 * `{"state":"stopped|starting|running|failed","port":N,"pid":N,"error":"…"}`.
 */
object LlamaServerNative {

    /**
     * Starts (or reuses) the forked inference child for `(modelPath, nCtx, backend)`;
     * blocks until the child is ready or the start failed. `configJson` carries the
     * sampling preset read from the installed model pack's meta.
     */
    external fun startServer(modelPath: String, nCtx: Int, backend: String, configJson: String): String

    /** Stops the child (SIGTERM → 3s → SIGKILL escalation). */
    external fun stopServer(): String

    /** Returns the lifecycle status JSON. */
    external fun serverStatus(): String
}
