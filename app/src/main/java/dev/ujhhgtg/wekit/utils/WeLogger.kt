package dev.ujhhgtg.wekit.utils

import android.util.Log
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {
    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // Guarded by `this`.
    private var output: FileOutputStream? = null
    private var currentLogDate: LocalDate? = null

    // ========== File Logging Internals ==========

    private fun getOrRotateOutput(logDate: LocalDate): FileOutputStream? {
        if (output != null && currentLogDate == logDate) return output

        output?.runCatching { close() }
        output = null
        currentLogDate = null

        val logsDir =
            runCatching {
                (KnownPaths.moduleRoot / "logs").createDirsSafe()
            }
                .getOrNull() ?: return null

        // Clean up logs older than 3 days during rotation/initialization
        deleteOldLogs(logsDir)

        val logPath = logsDir / "wekit-${dateFmt.format(logDate)}.log"

        return runCatching {
            FileOutputStream(logPath.toFile(), true).also {
                output = it
                currentLogDate = logDate
            }
        }
            .getOrNull()
    }

    private fun deleteOldLogs(logsDir: java.nio.file.Path) {
        runCatching {
            val thresholdDate = LocalDate.now().minusDays(3)
            val logFileRegex = Regex("""wekit-(\d{4}-\d{2}-\d{2})\.log""")

            logsDir.toFile().listFiles()?.forEach { file ->
                val match = logFileRegex.matchEntire(file.name)
                if (match != null) {
                    val dateStr = match.groupValues[1]
                    val fileDate = runCatching { LocalDate.parse(dateStr, dateFmt) }.getOrNull()

                    // If the log file date is older than 3 days ago, delete it
                    if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                        file.delete()
                    }
                }
            }
        }
    }

    /**
     * Writes one record synchronously. The stream is unbuffered and each record goes out in a
     * single append-mode `write()`, so once this returns the bytes are in the kernel page cache:
     * they survive the process being killed right afterwards, and records from different threads or
     * WeChat processes sharing the file never interleave.
     */
    private fun writeRecord(level: String, tag: String?, msg: String, throwable: Throwable?) {
        val timestamp = LocalDateTime.now()
        val bytes = buildString {
            append(timestampFmt.format(timestamp))
            append(' ')
            append(level)
            append('/')
            append(TAG)
            append(' ')
            append(tag)
            append(": ")
            append(msg)
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable))
            }
            append('\n')
        }
            .toByteArray()

        synchronized(this) {
            val out = getOrRotateOutput(timestamp.toLocalDate()) ?: return
            runCatching { out.write(bytes) }
                .onFailure { Log.e(TAG, "failed to write log file", it) }
        }
    }

    // ========== File Logging: public accessors (for the log viewer UI) ==========

    /** The directory run logs are written to (`filesDir/wekit/logs`), created on first access. */
    val logsDir: java.nio.file.Path?
        get() = runCatching { (KnownPaths.moduleRoot / "logs").createDirsSafe() }.getOrNull()

    /** All run-log files (`wekit-yyyy-MM-dd.log`), newest first. */
    val allLogFiles: List<java.nio.file.Path>
        get() {
            val dir = logsDir ?: return emptyList()
            val regex = Regex("""wekit-\d{4}-\d{2}-\d{2}\.log""")
            return runCatching {
                    dir.toFile()
                        .listFiles()
                        ?.filter { it.isFile && regex.matches(it.name) }
                        ?.sortedByDescending { it.name }
                        ?.map { it.toPath() } ?: emptyList()
                }
                .getOrDefault(emptyList())
        }

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        writeRecord("E", tag, msg, null)
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        writeRecord("W", tag, msg, null)
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        writeRecord("I", tag, msg, null)
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        writeRecord("D", tag, msg, null)
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        writeRecord("V", tag, msg, null)
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        writeRecord("E", tag, msg, e)
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        writeRecord("W", tag, msg, e)
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        writeRecord("I", tag, msg, e)
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        writeRecord("D", tag, msg, e)
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        writeRecord("V", tag, msg, e)
    }

    // ========== Stack Trace ==========

    val currentStackTrace: String
        get() {
            return Thread.currentThread()
                .stackTrace
                .drop(2) // drop getStackTrace + this function
                .joinToString(separator = "\n") { element ->
                    "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
        }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            writeRecord(priority.toPriorityChar(), tag, msg, null)
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            val headMsg = "[chunked] too long ($len chars, $chunkCount chunks). head:\n$head"
            val truncMsg = "[chunked] truncated. consider writing to file for full dump."
            Log.println(priority, TAG, "$tag: $headMsg")
            Log.println(priority, TAG, "$tag: $truncMsg")
            writeRecord(priority.toPriorityChar(), tag, headMsg, null)
            writeRecord(priority.toPriorityChar(), tag, truncMsg, null)
            return
        }

        var i = 0
        var part = 1
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            writeRecord(priority.toPriorityChar(), tag, partMsg, null)
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)

    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String =
        when (this) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
}
