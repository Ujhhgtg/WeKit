package dev.ujhhgtg.wekit.agent.model.local

/**
 * JNI bridge to the wekit-llama native controller shipped in the llama-native
 * extension pack. The library must be System.load-ed (via
 * `NativeLoader.prepareLlamaLaunch`) before parent-controller calls. Parent
 * lifecycle methods return the controller's status JSON:
 * `{"state":"stopped|starting|running|failed","port":N,"pid":N,"error":"…"}`.
 */
object LlamaServerNative {

    /**
     * Starts (or reuses) the app_process inference child; blocks until the child
     * is ready or the start failed. `configJson` carries the sampling preset read
     * from the installed model pack's meta.
     */
    external fun startServer(
        bootstrapApkPath: String,
        nativeLibraryPath: String,
        modelPath: String,
        nCtx: Int,
        backend: String,
        configJson: String,
    ): String

    /** Blocks inside the fresh app_process image until its server exits. */
    external fun runServerProcess(
        modelPath: String,
        nCtx: Int,
        backend: String,
        configJson: String,
        statusFd: Int,
    ): Int

    /** Stops the child (SIGTERM → 3s → SIGKILL escalation). */
    external fun stopServer(): String

    /** Returns the lifecycle status JSON. */
    external fun serverStatus(): String
}
