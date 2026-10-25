package dev.ujhhgtg.wekit.utils

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.WeLogger.BUFFER_TARGET
import dev.ujhhgtg.wekit.utils.WeLogger.FLUSH_INTERVAL_MS
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.div

/**
 * Logger writing to logcat (synchronously, auto-chunked) and to a daily log file (asynchronously).
 *
 * Call-site cost for the file path is one small allocation plus a queue offer; all timestamp
 * formatting, encoding, buffering, rotation and I/O happen on a single background writer thread.
 *
 * File write policy: records are buffered and written in one `write()` call when the buffer reaches
 * [BUFFER_TARGET] chars, when [FLUSH_INTERVAL_MS] has elapsed since the oldest buffered record,
 * when an ERROR-or-higher record arrives, or when [flush] is called. Each `write()` contains only
 * whole records on an append-mode stream, so records from different WeChat processes never
 * interleave. Call [flush] if you need the buffered records on disk right now (e.g. in an
 * uncaught-exception handler); anything still buffered or queued when the process is killed is
 * lost.
 */
object WeLogger {
    private const val TAG = BuildConfig.TAG

    // ---- logcat ----
    // logd payload limit is ~4068 BYTES (priority + tag + message), not chars. Budget is in bytes.
    private const val LOGCAT_PAYLOAD_BUDGET = 4000
    private const val MAX_LOGCAT_CHUNKS = 200
    private const val PART_LABEL_RESERVE = 16 // "[200/200] "
    private val logcatBudget = LOGCAT_PAYLOAD_BUDGET - utf8Len(TAG)

    // ---- file ----
    private const val QUEUE_CAPACITY = 16384
    private const val BATCH_MAX = 512
    private const val BUFFER_TARGET = 32 * 1024 // chars
    private const val MAX_RETAINED_BUFFER = 1 shl 20 // chars; shrink the buffer after a huge burst
    private const val FLUSH_INTERVAL_MS = 200L
    private const val FLUSH_INTERVAL_NS = FLUSH_INTERVAL_MS * 1_000_000L
    private const val MAX_FILE_CHARS = 1_000_000 // per record
    private const val RETRY_OPEN_MS = 10_000L
    private const val KEEP_DAYS = 3L
    private const val LEVEL_CHARS = "??VDIWEA" // indexed by android.util.Log priority

    private val logFileRegex = Regex("""wekit-(\d{4}-\d{2}-\d{2})\.log""")
    private val secFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private sealed interface Item

    private class Record(val timeMs: Long, val priority: Int, val tag: String?, val text: String) :
        Item

    private class FlushMarker : Item {
        val latch = CountDownLatch(1)
    }

    private val queue = LinkedBlockingQueue<Item>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger()

    // ---- state confined to the writer thread (no locking needed) ----
    private var sb = StringBuilder(BUFFER_TARGET + 4096)
    private var pendingSince = 0L
    private var bufferDay = ""
    private var output: FileOutputStream? = null
    private var openDay = ""
    private var retryAt = 0L
    private var cachedSec = Long.MIN_VALUE
    private var cachedPrefix = ""
    private var cachedDay = ""

    init {
        Thread(::writerLoop, "WeLogger-writer").apply { isDaemon = true }.start()
    }

    // ========== Public API ==========

    fun e(tag: String?, msg: String, e: Throwable? = null) = log(Log.ERROR, tag, msg, e)

    fun w(tag: String?, msg: String, e: Throwable? = null) = log(Log.WARN, tag, msg, e)

    fun i(tag: String?, msg: String, e: Throwable? = null) = log(Log.INFO, tag, msg, e)

    fun d(tag: String?, msg: String, e: Throwable? = null) = log(Log.DEBUG, tag, msg, e)

    fun v(tag: String?, msg: String, e: Throwable? = null) = log(Log.VERBOSE, tag, msg, e)

    /**
     * Blocks until every record logged by the calling thread before this call has been written to
     * the log file (i.e. handed to the OS). Returns false on timeout / interruption.
     */
    fun flush(timeoutMs: Long = 2000): Boolean {
        val marker = FlushMarker()
        return try {
            queue.offer(marker, timeoutMs, TimeUnit.MILLISECONDS) &&
                marker.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** The directory run logs are written to, created on first access. */
    val logsDir: Path?
        get() = runCatching { (KnownPaths.moduleRoot / "logs").createDirsSafe() }.getOrNull()

    /** All run-log files (`wekit-yyyy-MM-dd.log`), newest first. */
    val allLogFiles: List<Path>
        get() {
            val dir = logsDir ?: return emptyList()
            return runCatching {
                    dir.toFile()
                        .listFiles()
                        ?.filter { it.isFile && logFileRegex.matches(it.name) }
                        ?.sortedByDescending { it.name }
                        ?.map { it.toPath() } ?: emptyList()
                }
                .getOrDefault(emptyList())
        }

    // ========== Caller side ==========

    private fun log(priority: Int, tag: String?, msg: String, t: Throwable?) {
        // Stack trace is rendered once and shared by logcat (which would otherwise truncate it at
        // the payload limit) and the file.
        val text = if (t == null) msg else msg + '\n' + Log.getStackTraceString(t)
        printToLogcat(priority, tag, text)
        if (!queue.offer(Record(System.currentTimeMillis(), priority, tag, text))) {
            dropped.incrementAndGet()
        }
    }

    private fun printToLogcat(priority: Int, tag: String?, text: String) {
        val prefix = if (tag == null) "" else "$tag: "
        // Fast path: even at 3 bytes/char this fits, so no scanning or splitting needed.
        if ((prefix.length + text.length) * 3 <= logcatBudget) {
            Log.println(priority, TAG, if (prefix.isEmpty()) text else prefix + text)
            return
        }
        printChunked(priority, prefix, text)
    }

    /** Splits by UTF-8 byte size (never inside a surrogate pair), preferring newline boundaries. */
    private fun printChunked(priority: Int, prefix: String, text: String) {
        val limit = logcatBudget - utf8Len(prefix) - PART_LABEL_RESERVE
        val ends = IntArray(MAX_LOGCAT_CHUNKS + 1)
        var n = 0
        var start = 0
        while (start < text.length && n <= MAX_LOGCAT_CHUNKS) {
            val end = chunkEnd(text, start, limit)
            ends[n++] = end
            start = end
        }
        val truncated = n > MAX_LOGCAT_CHUNKS
        val count = if (truncated) MAX_LOGCAT_CHUNKS else n

        start = 0
        for (k in 0 until count) {
            val end = ends[k]
            val shown = end - if (text[end - 1] == '\n') 1 else 0
            Log.println(priority, TAG, "$prefix[${k + 1}/$count] ${text.substring(start, shown)}")
            start = end
        }
        if (truncated) {
            Log.println(
                priority,
                TAG,
                "$prefix[truncated] ${text.length - start} more chars omitted from logcat; see log file",
            )
        }
    }

    private fun chunkEnd(text: String, start: Int, limitBytes: Int): Int {
        val len = text.length
        var bytes = 0
        var i = start
        var lastNewline = -1
        while (i < len) {
            val c = text[i]
            val w: Int
            val step: Int
            when {
                c.code < 0x80 -> {
                    w = 1
                    step = 1
                }
                c.code < 0x800 -> {
                    w = 2
                    step = 1
                }
                c.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate() -> {
                    w = 4
                    step = 2
                }
                else -> {
                    w = 3
                    step = 1
                }
            }
            if (bytes + w > limitBytes) break
            bytes += w
            i += step
            if (c == '\n') lastNewline = i
        }
        // Prefer breaking at a newline if it keeps at least half of the chunk.
        if (i < len && lastNewline > start && lastNewline - start >= (i - start) / 2)
            return lastNewline
        return i
    }

    private fun utf8Len(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }

    // ========== Writer thread ==========

    private fun writerLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val batch = ArrayList<Item>(BATCH_MAX)
        while (true) {
            try {
                val first: Item? =
                    if (sb.isEmpty()) {
                        queue.take()
                    } else {
                        val left = FLUSH_INTERVAL_NS - (System.nanoTime() - pendingSince)
                        if (left > 0) queue.poll(left, TimeUnit.NANOSECONDS) else null
                    }
                if (first == null) { // buffered data aged out
                    writeOut()
                    continue
                }
                batch.add(first)
                queue.drainTo(batch, BATCH_MAX - 1)

                var force = false
                for (item in batch) {
                    when (item) {
                        is Record -> {
                            appendRecord(item.timeMs, item.priority, item.tag, item.text)
                            if (item.priority >= Log.ERROR) force = true
                        }
                        is FlushMarker -> {
                            writeOut()
                            item.latch.countDown()
                        }
                    }
                }
                val d = dropped.getAndSet(0)
                if (d > 0) {
                    appendRecord(
                        System.currentTimeMillis(),
                        Log.WARN,
                        "WeLogger",
                        "dropped $d records (queue full)",
                    )
                }
                if (force || sb.length >= BUFFER_TARGET) writeOut()
            } catch (_: InterruptedException) {
                // ignore, keep running
            } catch (t: Throwable) {
                Log.e(TAG, "log writer failure", t)
            } finally {
                batch.clear()
            }
        }
    }

    private fun tsPrefix(ms: Long): String {
        val sec = Math.floorDiv(ms, 1000L)
        if (sec != cachedSec) {
            cachedPrefix =
                secFmt.format(
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault()),
                )
            cachedDay = cachedPrefix.substring(0, 10)
            cachedSec = sec
        }
        return cachedPrefix
    }

    private fun appendRecord(ms: Long, priority: Int, tag: String?, text: String) {
        val prefix = tsPrefix(ms)
        // Never mix two days in one buffer; the buffer is written to the file of its own day.
        if (sb.isNotEmpty() && cachedDay != bufferDay) writeOut()
        if (sb.isEmpty()) {
            bufferDay = cachedDay
            pendingSince = System.nanoTime()
        }
        val milli = Math.floorMod(ms, 1000L).toInt()
        sb.append(prefix).append('.')
        if (milli < 100) sb.append('0')
        if (milli < 10) sb.append('0')
        sb.append(milli)
        sb.append(' ')
            .append(if (priority in 2..7) LEVEL_CHARS[priority] else '?')
            .append('/')
            .append(TAG)
            .append(' ')
        if (tag != null) sb.append(tag).append(": ")
        if (text.length > MAX_FILE_CHARS) {
            sb.append(text, 0, MAX_FILE_CHARS)
                .append("\n[truncated ")
                .append(text.length - MAX_FILE_CHARS)
                .append(" chars]")
        } else {
            sb.append(text)
        }
        sb.append('\n')
    }

    private fun writeOut() {
        if (sb.isEmpty()) return
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        if (sb.capacity() > MAX_RETAINED_BUFFER) sb = StringBuilder(BUFFER_TARGET + 4096)
        else sb.setLength(0)

        val out = ensureOutput(bufferDay) ?: return // file unavailable: records are dropped
        try {
            out.write(bytes) // single append-mode write of whole records
        } catch (e: IOException) {
            Log.e(TAG, "failed to write log file", e)
            closeOutput()
            retryAt = SystemClock.elapsedRealtime() + RETRY_OPEN_MS
        }
    }

    private fun ensureOutput(day: String): FileOutputStream? {
        val cur = output
        if (cur != null && openDay == day) return cur
        closeOutput()

        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return null // don't hammer mkdirs/open while the fs is failing

        val dir = logsDir
        val stream =
            if (dir == null) null
            else {
                deleteOldLogs(dir, day)
                runCatching { FileOutputStream((dir / "wekit-$day.log").toFile(), true) }
                    .getOrNull()
            }
        if (stream == null) {
            retryAt = now + RETRY_OPEN_MS
            return null
        }
        output = stream
        openDay = day
        return stream
    }

    private fun closeOutput() {
        output?.runCatching { close() }
        output = null
        openDay = ""
    }

    private fun deleteOldLogs(dir: Path, today: String) {
        runCatching {
            val threshold = LocalDate.parse(today).minusDays(KEEP_DAYS)
            dir.toFile().listFiles()?.forEach { file ->
                val m = logFileRegex.matchEntire(file.name) ?: return@forEach
                val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
                if (date != null && date.isBefore(threshold)) file.delete()
            }
        }
    }
}
