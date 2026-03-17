package moe.ouom.wekit.utils.crash

import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.ModulePaths
import moe.ouom.wekit.utils.logging.WeLogger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CrashLogsManager {

    private val crashLogsDir: Path by lazy { ModulePaths.data!! / CRASH_LOGS_DIR }

    init {
        ensureCrashLogDirExists()
    }

    private fun ensureCrashLogDirExists() {
        if (!crashLogsDir.exists()) {
            if (runCatching { crashLogsDir.createDirectories() }.isSuccess) {
                WeLogger.i(TAG, "Crash log directory created: ${crashLogsDir.absolutePathString()}")
            } else {
                WeLogger.e(TAG, "Failed to create crash log directory")
            }
        }
    }

    fun saveCrashLog(crashInfo: String, isJavaCrash: Boolean = false): String? {
        return try {
            ensureCrashLogDirExists()

            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            val fileName = CRASH_LOGS_PREFIX + sdf.format(Date()) + CRASH_LOG_SUFFIX
            val logFile = crashLogsDir / fileName

            logFile.writeText(crashInfo)
            WeLogger.i(TAG, "Crash log saved: ${logFile.absolutePathString()}")

            if (isJavaCrash) setPendingJavaCrashFlag(logFile.name)
            else setPendingCrashFlag(logFile.name)

            cleanOldLogs()
            logFile.absolutePathString()
        } catch (e: IOException) {
            WeLogger.e("Failed to save crash log", e)
            null
        }
    }

    val allCrashLogs: List<Path>
        get() {
            ensureCrashLogDirExists()
            return crashLogsDir.listDirectoryEntries()
                .filter { it.name.startsWith(CRASH_LOGS_PREFIX) && it.name.endsWith(CRASH_LOG_SUFFIX) }
                .sortedByDescending { it.getLastModifiedTime() }
        }

    fun readCrashLog(logFile: Path): String? {
        return try {
            if (!logFile.exists() || !logFile.isRegularFile()) return null

            val fileSize = logFile.fileSize()
            if (fileSize > MAX_LOG_CONTENT_SIZE) {
                WeLogger.w(
                    TAG,
                    "Crash log file is too large ($fileSize bytes), reading first $MAX_LOG_CONTENT_SIZE bytes"
                )
                val buffer = ByteArray(MAX_LOG_CONTENT_SIZE)
                val bytesRead = logFile.inputStream().use { it.read(buffer) }
                String(buffer, 0, bytesRead, StandardCharsets.UTF_8) +
                        "\n\n========================================\n" +
                        "【提示】日志内容过长，此处仅展示部分内容。\n" +
                        "请点击「导出文件」以保存完整日志。\n" +
                        "========================================"
            } else {
                logFile.readText()
            }
        } catch (e: IOException) {
            WeLogger.e("Failed to read crash log", e)
            null
        }
    }

    fun readFullCrashLog(logFile: Path): String? {
        return try {
            if (!logFile.exists() || !logFile.isRegularFile()) return null
            WeLogger.d(TAG, "Reading full crash log, size: ${logFile.fileSize()} bytes")
            logFile.readText()
        } catch (e: IOException) {
            WeLogger.e("Failed to read full crash log", e)
            null
        }
    }

    fun deleteCrashLog(logFile: Path): Boolean {
        return if (logFile.exists() && runCatching { logFile.deleteExisting() }.isSuccess) {
            WeLogger.i(TAG, "Crash log deleted: ${logFile.name}")
            true
        } else false
    }

    fun deleteAllCrashLogs(): Int {
        val count = allCrashLogs.count { deleteCrashLog(it) }
        clearPendingCrashFlag()
        WeLogger.i(TAG, "Deleted $count crash logs")
        return count
    }

    private fun cleanOldLogs() {
        val logFiles = allCrashLogs
        if (logFiles.size > MAX_LOG_FILES) {
            WeLogger.i(TAG, "Cleaning old crash logs, current count: ${logFiles.size}")
            logFiles.drop(MAX_LOG_FILES).forEach { deleteCrashLog(it) }
        }
    }

    private fun setPendingCrashFlag(logFileName: String) {
        try {
            (crashLogsDir / PENDING_CRASH_FLAG).writeText(logFileName)
            WeLogger.d(TAG, "Pending crash flag set: $logFileName")
        } catch (e: IOException) {
            WeLogger.e("Failed to set pending crash flag", e)
        }
    }

    val pendingCrashLogFileName: String?
        get() = readFlagFile(PENDING_CRASH_FLAG, "pending crash")

    val pendingCrashLogFile: Path?
        get() {
            val fileName = pendingCrashLogFileName ?: return null
            val logFile = crashLogsDir / fileName
            if (logFile.exists() && logFile.isRegularFile()) return logFile
            clearPendingCrashFlag()
            return null
        }

    fun clearPendingCrashFlag() {
        deleteFlagFile(PENDING_CRASH_FLAG, "Pending crash flag cleared")
    }

    fun hasPendingCrash(): Boolean = pendingCrashLogFile != null

    val crashLogDirPath: String get() = crashLogsDir.absolutePathString()

    fun setPendingJavaCrashFlag(logFileName: String) {
        try {
            (crashLogsDir / PENDING_JAVA_CRASH_FLAG).writeText(logFileName)
            WeLogger.d(TAG, "Pending Java crash flag set: $logFileName")
        } catch (e: IOException) {
            WeLogger.e("Failed to set pending Java crash flag", e)
        }
    }

    val pendingJavaCrashLogFileName: String?
        get() = readFlagFile(PENDING_JAVA_CRASH_FLAG, "pending Java crash")

    val pendingJavaCrashLogFile: Path?
        get() {
            val fileName = pendingJavaCrashLogFileName ?: return null
            val logFile = crashLogsDir / fileName
            if (logFile.exists() && logFile.isRegularFile()) return logFile
            clearPendingJavaCrashFlag()
            return null
        }

    fun clearPendingJavaCrashFlag() {
        deleteFlagFile(PENDING_JAVA_CRASH_FLAG, "Pending Java crash flag cleared")
    }

    fun hasPendingJavaCrash(): Boolean = pendingJavaCrashLogFile != null

    val pendingNativeCrashLogFile: Path? get() = pendingCrashLogFile

    fun clearPendingNativeCrashFlag() = clearPendingCrashFlag()

    fun hasPendingNativeCrash(): Boolean = hasPendingCrash()

    private fun readFlagFile(flagFileName: String, logLabel: String): String? {
        return try {
            val flagFile = crashLogsDir / flagFileName
            if (!flagFile.exists()) return null
            val fileName = flagFile.readText().trim()
            WeLogger.d(TAG, "Pending $logLabel log: $fileName")
            fileName
        } catch (e: IOException) {
            WeLogger.e("Failed to get $logLabel flag", e)
            null
        }
    }

    private fun deleteFlagFile(flagFileName: String, logMessage: String) {
        val flagFile = crashLogsDir / flagFileName
        if (flagFile.exists() && runCatching { flagFile.deleteExisting() }.isSuccess) {
            WeLogger.d(TAG, logMessage)
        }
    }

    companion object {
        private val TAG = nameof(CrashLogsManager::class)

        private const val CRASH_LOGS_DIR = "crash_logs"
        private const val CRASH_LOGS_PREFIX = "crash_"
        private const val CRASH_LOG_SUFFIX = ".log"
        private const val PENDING_CRASH_FLAG = "pending_crash.flag"
        private const val PENDING_JAVA_CRASH_FLAG = "pending_java_crash.flag"
        private const val MAX_LOG_FILES = 50
        private const val MAX_LOG_CONTENT_SIZE = 30 * 1024
    }
}
