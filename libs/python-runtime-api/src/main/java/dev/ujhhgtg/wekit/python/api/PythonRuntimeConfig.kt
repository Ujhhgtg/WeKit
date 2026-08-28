package dev.ujhhgtg.wekit.python.api

import android.app.Application
import java.io.File

data class PythonRuntimeConfig(
    val application: Application,
    val runtimeApk: File,
    val nativeDirectory: File,
    val sdkRoot: File,
    val lookupClassLoader: ClassLoader,
    val syncHookBudgetMs: Long,
    val taskDrainTimeoutMs: Long,
    val maxManifestBytes: Long,
    val maxPluginFileBytes: Long,
) {
    init {
        requireFinitePositive("syncHookBudgetMs", syncHookBudgetMs)
        requireFinitePositive("taskDrainTimeoutMs", taskDrainTimeoutMs)
        requireFinitePositive("maxManifestBytes", maxManifestBytes)
        requireFinitePositive("maxPluginFileBytes", maxPluginFileBytes)
    }

    private fun requireFinitePositive(name: String, value: Long) {
        require(value > 0L && value < Long.MAX_VALUE) {
            "$name must be finite and positive: $value"
        }
    }
}
