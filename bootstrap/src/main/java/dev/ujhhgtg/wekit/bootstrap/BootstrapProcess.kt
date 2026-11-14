package dev.ujhhgtg.wekit.bootstrap

import android.app.Application
import java.io.File

/**
 * The bootstrap only runs in the host processes that WeKit targets: the main process, `:push`,
 * the appbrand processes and the normsg data-service process (`:hotpot..`). Every other injected
 * process returns before any initialization or network request.
 */
object BootstrapProcess {
    fun isTargeted(processName: String): Boolean {
        val parts = processName.split(":")
        if (parts.size == 1) return true
        val tail = parts.last()
        return tail == "push" || tail.startsWith("appbrand") || tail.startsWith("hotpot")
    }

    fun isCurrentProcessTargeted(): Boolean = isTargeted(currentProcessName() ?: return false)

    private fun currentProcessName(): String? =
        Application.getProcessName() ?: runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
}
