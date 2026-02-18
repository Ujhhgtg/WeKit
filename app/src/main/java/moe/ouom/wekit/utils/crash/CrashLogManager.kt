package moe.ouom.wekit.utils.crash

import android.content.Context
import moe.ouom.wekit.utils.log.WeLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogManager(context: Context) {
    private val crashLogDir: File

    init {
        val context1 = context.applicationContext
        this.crashLogDir = File(context1.filesDir, CRASH_LOG_DIR)
        ensureCrashLogDirExists()
    }

    /**
     * 确保崩溃日志目录存在
     */
    private fun ensureCrashLogDirExists() {
        if (!crashLogDir.exists()) {
            if (crashLogDir.mkdirs()) {
                WeLogger.i(
                    "CrashLogManager",
                    "Crash log directory created: " + crashLogDir.absolutePath
                )
            } else {
                WeLogger.e("CrashLogManager", "Failed to create crash log directory")
            }
        }
    }

    /**
     * 保存崩溃日志
     * 
     * @param crashInfo   崩溃信息
     * @param isJavaCrash 是否为Java崩溃（true=Java, false=Native）
     * @return 保存的文件路径，失败返回null
     */
    fun saveCrashLog(crashInfo: String, isJavaCrash: Boolean = false): String? {
        try {
            ensureCrashLogDirExists()

            // 生成文件名：crash_yyyyMMdd_HHmmss_SSS.log
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            val fileName: String = CRASH_LOG_PREFIX + sdf.format(Date()) + CRASH_LOG_SUFFIX
            val logFile = File(crashLogDir, fileName)

            // 写入崩溃信息
            val writer = FileWriter(logFile)
            writer.write(crashInfo)
            writer.flush()
            writer.close()

            WeLogger.i("CrashLogManager", "Crash log saved: " + logFile.absolutePath)

            // 根据崩溃类型设置不同的待处理标记
            if (isJavaCrash) {
                setPendingJavaCrashFlag(logFile.name)
            } else {
                setPendingCrashFlag(logFile.name)
            }

            // 清理旧日志
            cleanOldLogs()

            return logFile.absolutePath
        } catch (e: IOException) {
            WeLogger.e("[CrashLogManager] Failed to save crash log", e)
            return null
        }
    }

    val allCrashLogs: List<File>
        /**
         * 获取所有崩溃日志文件
         * 
         * @return 崩溃日志文件列表，按时间倒序排列
         */
        get() {
            ensureCrashLogDirExists()

            val files =
                crashLogDir.listFiles { _: File?, name: String? ->
                    name!!.startsWith(CRASH_LOG_PREFIX) && name.endsWith(
                        CRASH_LOG_SUFFIX
                    )
                }

            if (files == null || files.size == 0) {
                return ArrayList()
            }

            val logFiles = files.toMutableList()

            // 按修改时间倒序排列（最新的在前面）
            logFiles.sortWith(Comparator { f1: File?, f2: File? ->
                f2!!.lastModified().compareTo(f1!!.lastModified())
            })

            return logFiles
        }

    /**
     * 读取崩溃日志内容
     * （用于UI显示，会截断过长内容）
     * 
     * @param logFile 日志文件
     * @return 日志内容，失败返回null
     */
    fun readCrashLog(logFile: File): String? {
        try {
            if (!logFile.exists() || !logFile.isFile) {
                return null
            }

            val fileSize = logFile.length()

            // 如果文件太大，只读取前面部分并添加提示
            if (fileSize > MAX_LOG_CONTENT_SIZE) {
                WeLogger.w(
                    "CrashLogManager",
                    "Crash log file is too large ($fileSize bytes), reading first $MAX_LOG_CONTENT_SIZE bytes"
                )

                val fis = FileInputStream(logFile)
                val buffer = ByteArray(MAX_LOG_CONTENT_SIZE)
                val bytesRead = fis.read(buffer)
                fis.close()

                val content = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                return content + "\n\n========================================\n" +
                        "【提示】日志内容过长，此处仅展示部分内容。\n" +
                        "请点击「导出文件」以保存完整日志。\n" +
                        "========================================"
            }

            // 正常大小的文件，完整读取
            val fis = FileInputStream(logFile)
            val buffer = ByteArray(fileSize.toInt())
            fis.read(buffer)
            fis.close()

            return String(buffer, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            WeLogger.e("[CrashLogManager] Failed to read crash log", e)
            return null
        }
    }

    /**
     * 读取完整的崩溃日志内容
     * 
     * @param logFile 日志文件
     * @return 完整的日志内容，失败返回null
     */
    fun readFullCrashLog(logFile: File): String? {
        try {
            if (!logFile.exists() || !logFile.isFile) {
                return null
            }

            val fileSize = logFile.length()
            WeLogger.d("CrashLogManager", "Reading full crash log, size: $fileSize bytes")

            // 读取完整文件内容，不进行截断
            val fis = FileInputStream(logFile)
            val buffer = ByteArray(fileSize.toInt())
            fis.read(buffer)
            fis.close()

            return String(buffer, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            WeLogger.e("[CrashLogManager] Failed to read full crash log", e)
            return null
        }
    }

    /**
     * 删除崩溃日志
     * 
     * @param logFile 日志文件
     * @return 是否删除成功
     */
    fun deleteCrashLog(logFile: File): Boolean {
        if (logFile.exists() && logFile.delete()) {
            WeLogger.i("CrashLogManager", "Crash log deleted: " + logFile.name)
            return true
        }
        return false
    }

    /**
     * 删除所有崩溃日志
     * 
     * @return 删除的文件数量
     */
    fun deleteAllCrashLogs(): Int {
        val logFiles = this.allCrashLogs
        var count = 0
        for (file in logFiles) {
            if (deleteCrashLog(file)) {
                count++
            }
        }
        clearPendingCrashFlag()
        WeLogger.i("CrashLogManager", "Deleted $count crash logs")
        return count
    }

    /**
     * 清理旧日志，保留最新的MAX_LOG_FILES个
     */
    private fun cleanOldLogs() {
        val logFiles = this.allCrashLogs
        if (logFiles.size > MAX_LOG_FILES) {
            WeLogger.i(
                "CrashLogManager",
                "Cleaning old crash logs, current count: " + logFiles.size
            )
            for (i in MAX_LOG_FILES..<logFiles.size) {
                deleteCrashLog(logFiles.get(i))
            }
        }
    }

    /**
     * 设置待处理崩溃标记
     * 
     * @param logFileName 崩溃日志文件名
     */
    private fun setPendingCrashFlag(logFileName: String) {
        try {
            val flagFile = File(crashLogDir, PENDING_CRASH_FLAG)
            val writer = FileWriter(flagFile)
            writer.write(logFileName)
            writer.flush()
            writer.close()
            WeLogger.d("CrashLogManager", "Pending crash flag set: $logFileName")
        } catch (e: IOException) {
            WeLogger.e("[CrashLogManager] Failed to set pending crash flag", e)
        }
    }

    val pendingCrashLogFileName: String?
        /**
         * 获取待处理的崩溃日志文件名
         * 
         * @return 崩溃日志文件名，如果没有则返回null
         */
        get() {
            try {
                val flagFile =
                    File(crashLogDir, PENDING_CRASH_FLAG)
                if (!flagFile.exists()) {
                    return null
                }

                val fis = FileInputStream(flagFile)
                val buffer = ByteArray(flagFile.length().toInt())
                fis.read(buffer)
                fis.close()

                val fileName =
                    String(buffer, StandardCharsets.UTF_8)
                        .trim { it <= ' ' }
                WeLogger.d("CrashLogManager", "Pending crash log: $fileName")
                return fileName
            } catch (e: IOException) {
                WeLogger.e("[CrashLogManager] Failed to get pending crash flag", e)
                return null
            }
        }

    val pendingCrashLogFile: File?
        /**
         * 获取待处理的崩溃日志文件
         * 
         * @return 崩溃日志文件，如果没有则返回 null
         */
        get() {
            val fileName = this.pendingCrashLogFileName ?: return null

            val logFile = File(crashLogDir, fileName)
            if (logFile.exists() && logFile.isFile) {
                return logFile
            }

            // 文件不存在，清除标记
            clearPendingCrashFlag()
            return null
        }

    /**
     * 清除待处理崩溃标记
     */
    fun clearPendingCrashFlag() {
        val flagFile = File(crashLogDir, PENDING_CRASH_FLAG)
        if (flagFile.exists() && flagFile.delete()) {
            WeLogger.d("CrashLogManager", "Pending crash flag cleared")
        }
    }

    /**
     * 检查是否有待处理的崩溃
     * 
     * @return 是否有待处理的崩溃
     */
    fun hasPendingCrash(): Boolean {
        return this.pendingCrashLogFile != null
    }

    val crashLogDirPath: String
        /**
         * 获取崩溃日志目录路径
         * 
         * @return 崩溃日志目录路径
         */
        get() = crashLogDir.absolutePath

    val crashLogCount: Int
        /**
         * 获取崩溃日志数量
         * 
         * @return 崩溃日志数量
         */
        get() = this.allCrashLogs.size

    // ==================== Java 崩溃专用方法 ====================
    /**
     * 设置待处理Java崩溃标记
     * 
     * @param logFileName 崩溃日志文件名
     */
    fun setPendingJavaCrashFlag(logFileName: String) {
        try {
            val flagFile = File(crashLogDir, PENDING_JAVA_CRASH_FLAG)
            val writer = FileWriter(flagFile)
            writer.write(logFileName)
            writer.flush()
            writer.close()
            WeLogger.d("CrashLogManager", "Pending Java crash flag set: $logFileName")
        } catch (e: IOException) {
            WeLogger.e("[CrashLogManager] Failed to set pending Java crash flag", e)
        }
    }

    val pendingJavaCrashLogFileName: String?
        /**
         * 获取待处理的Java崩溃日志文件名
         * 
         * @return 崩溃日志文件名，如果没有则返回null
         */
        get() {
            try {
                val flagFile =
                    File(crashLogDir, PENDING_JAVA_CRASH_FLAG)
                if (!flagFile.exists()) {
                    return null
                }

                val fis = FileInputStream(flagFile)
                val buffer = ByteArray(flagFile.length().toInt())
                fis.read(buffer)
                fis.close()

                val fileName =
                    String(buffer, StandardCharsets.UTF_8)
                        .trim { it <= ' ' }
                WeLogger.d("CrashLogManager", "Pending Java crash log: $fileName")
                return fileName
            } catch (e: IOException) {
                WeLogger.e("[CrashLogManager] Failed to get pending Java crash flag", e)
                return null
            }
        }

    val pendingJavaCrashLogFile: File?
        /**
         * 获取待处理的Java崩溃日志文件
         * 
         * @return 崩溃日志文件，如果没有则返回 null
         */
        get() {
            val fileName = this.pendingJavaCrashLogFileName ?: return null

            val logFile = File(crashLogDir, fileName)
            if (logFile.exists() && logFile.isFile) {
                return logFile
            }

            // 文件不存在，清除标记
            clearPendingJavaCrashFlag()
            return null
        }

    /**
     * 清除待处理Java崩溃标记
     */
    fun clearPendingJavaCrashFlag() {
        val flagFile = File(crashLogDir, PENDING_JAVA_CRASH_FLAG)
        if (flagFile.exists() && flagFile.delete()) {
            WeLogger.d("CrashLogManager", "Pending Java crash flag cleared")
        }
    }

    /**
     * 检查是否有待处理的Java崩溃
     * 
     * @return 是否有待处理的Java崩溃
     */
    fun hasPendingJavaCrash(): Boolean {
        return this.pendingJavaCrashLogFile != null
    }

    // ==================== Native 崩溃专用方法 ====================
    val pendingNativeCrashLogFileName: String?
        /**
         * 获取待处理的Native崩溃日志文件名
         * Native崩溃使用 pending_crash.flag
         * 
         * @return 崩溃日志文件名，如果没有则返回null
         */
        get() = this.pendingCrashLogFileName

    val pendingNativeCrashLogFile: File?
        /**
         * 获取待处理的Native崩溃日志文件
         * 
         * @return 崩溃日志文件，如果没有则返回 null
         */
        get() = this.pendingCrashLogFile

    /**
     * 清除待处理Native崩溃标记
     */
    fun clearPendingNativeCrashFlag() {
        clearPendingCrashFlag()
    }

    /**
     * 检查是否有待处理的Native崩溃
     * 
     * @return 是否有待处理的Native崩溃
     */
    fun hasPendingNativeCrash(): Boolean {
        return hasPendingCrash()
    }

    companion object {
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val CRASH_LOG_PREFIX = "crash_"
        private const val CRASH_LOG_SUFFIX = ".log"
        private const val PENDING_CRASH_FLAG = "pending_crash.flag"
        private const val PENDING_JAVA_CRASH_FLAG = "pending_java_crash.flag"
        private const val MAX_LOG_FILES = 50 // 最多保留 50 个崩溃日志
        private val MAX_LOG_CONTENT_SIZE = 30 * 1024 // 最大读取30KB
    }
}
