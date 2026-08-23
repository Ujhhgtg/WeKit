package dev.ujhhgtg.wekit.features.items.debug

import android.os.Debug
import android.os.Process
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger

object ProfileMemory : ClickableFeature() {

    override val technicalId = "内存分析"
    override val nameRes = R.string.feature_profile_memory_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_profile_memory_description

    private const val TAG = "ProfileMemory"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        logMemorySnapshot()
    }

    private fun logMemorySnapshot() {
        val pid = Process.myPid()

        // 1. Debug.MemoryInfo — detailed PSS/RSS breakdown
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)

        // 2. Runtime heap stats
        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        val heapMaxKb = rt.maxMemory() / 1024

        // 3. /proc/self/status — native RSS
        val procStatus = readProcStatus()

        val snapshot = buildString {
            appendLine("=== $TAG snapshot (pid=$pid) ===")
            appendLine("PSS total      : ${mi.totalPss} kB")
            appendLine("- dalvik PSS   : ${mi.dalvikPss} kB")
            appendLine("- native PSS   : ${mi.nativePss} kB")
            appendLine("- other PSS    : ${mi.otherPss} kB")
            appendLine("Private dirty  : ${mi.totalPrivateDirty} kB")
            appendLine("Private clean  : ${mi.totalPrivateClean} kB")
            appendLine("Shared dirty   : ${mi.totalSharedDirty} kB")
            appendLine("Heap used (RT) : $heapUsedKb kB / $heapMaxKb kB")
            appendLine("Native VmRSS   : ${procStatus["VmRSS"]} kB")
            appendLine("Native VmPeak  : ${procStatus["VmPeak"]} kB")
            appendLine("Native VmSwap  : ${procStatus["VmSwap"]} kB")
            appendLine("Threads        : ${procStatus["Threads"]}")
        }

        WeLogger.d(TAG, "\n" + snapshot)
    }

    // ── /proc/self/status parser ──────────────────────────────────────────────

    private fun readProcStatus(): Map<String, String> = buildMap {
        runCatching {
            java.io.File("/proc/self/status").forEachLine { line ->
                val parts = line.split(":\\s+".toRegex(), limit = 2)
                if (parts.size == 2) put(parts[0].trim(), parts[1].trim().removeSuffix(" kB"))
            }
        }.onFailure { WeLogger.e(TAG, "/proc read failed", it) }
    }
}
