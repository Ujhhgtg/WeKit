package dev.ujhhgtg.wekit.utils

import android.util.Log
import dev.ujhhgtg.wekit.BuildConfig
import java.io.BufferedWriter
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {

    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200

    private val timestampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val lock = ReentrantLock()

    private var writer: BufferedWriter? = null
    private var currentLogDate: LocalDate? = null

    // ========== File Logging Internals ==========

    private fun getOrRotateWriter(): BufferedWriter? {
        val today = LocalDate.now()

        if (writer != null && currentLogDate == today) return writer

        writer?.runCatching { close() }
        writer = null

        val logPath = runCatching {
            val logsDir = (KnownPaths.moduleData / "logs").createDirectoriesNoThrow()
            logsDir / "wekit-${dateFmt.format(today)}.log"
        }.getOrNull() ?: return null

        return runCatching {
            BufferedWriter(FileWriter(logPath.toFile(), true)).also {
                writer = it
                currentLogDate = today
            }
        }.getOrNull()
    }

    private fun writeToFile(level: String, tag: String?, msg: String, throwable: Throwable? = null) {
        lock.withLock {
            val w = getOrRotateWriter() ?: return
            runCatching {
                val ts = timestampFmt.format(LocalDateTime.now())
                w.write(buildString {
                    append("$ts $level/$TAG $tag: $msg")
                    if (throwable != null) {
                        append('\n')
                        append(Log.getStackTraceString(throwable))
                    }
                })
                w.newLine()
            }
        }
    }

    /**
     * Flush buffered log writes to disk. Call this from crash handlers to ensure no logs are lost.
     */
    fun flush() {
        lock.withLock {
            writer?.runCatching { flush() }
        }
    }

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        writeToFile("E", tag, msg)
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        writeToFile("W", tag, msg)
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        writeToFile("I", tag, msg)
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        writeToFile("D", tag, msg)
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        writeToFile("V", tag, msg)
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        writeToFile("E", tag, msg, e)
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        writeToFile("W", tag, msg, e)
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        writeToFile("I", tag, msg, e)
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        writeToFile("D", tag, msg, e)
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        writeToFile("V", tag, msg, e)
    }

    // ========== Stack Trace ==========

    fun getStackTraceString(): String {
        val stackTrace = Thread.currentThread().stackTrace
        val stackTraceMsg = StringBuilder().append("\n")
        var startRecording = false

        for (element in stackTrace) {
            val className = element!!.className
            if (className.contains("LSPHooker")) {
                startRecording = true
                continue
            }
            if (!startRecording) continue
            if (className == Thread::class.java.name) continue

            stackTraceMsg.append(
                "  at %s.%s(%s:%d)\n".format(
                    Locale.ROOT,
                    element.className,
                    element.methodName,
                    element.fileName,
                    element.lineNumber
                )
            )
        }
        return stackTraceMsg.toString()
    }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            writeToFile(priority.toPriorityChar(), tag, msg)
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
            writeToFile(priority.toPriorityChar(), tag, headMsg)
            writeToFile(priority.toPriorityChar(), tag, truncMsg)
            return
        }

        var i = 0
        var part = 1
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            writeToFile(priority.toPriorityChar(), tag, partMsg)
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)
    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String = when (this) {
        Log.VERBOSE -> "V"
        Log.DEBUG   -> "D"
        Log.INFO    -> "I"
        Log.WARN    -> "W"
        Log.ERROR   -> "E"
        Log.ASSERT  -> "A"
        else        -> "?"
    }
}
