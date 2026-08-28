package dev.ujhhgtg.wekit.features.items.scripting_python.plugin

import java.io.File

enum class PythonPluginStatus {
    DISABLED,
    RUNTIME_MISSING,
    LOADING,
    ACTIVE,
    UNLOADING,
    FAILED,
    CRASH_SUSPECT,
}

data class PythonPluginRecord(
    val id: String,
    val root: File,
    val manifest: PythonPluginManifest?,
    val desiredEnabled: Boolean,
    val status: PythonPluginStatus,
    val lastError: String? = null,
    val traceback: String? = null,
)
