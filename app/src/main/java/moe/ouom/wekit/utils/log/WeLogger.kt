package moe.ouom.wekit.utils.log

import android.annotation.SuppressLint
import android.util.Log
import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.BuildConfig
import kotlin.math.min

object WeLogger {
    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200

    // ========== String ==========
    @JvmStatic
    fun e(msg: String) {
        Log.e(TAG, msg)
        try {
            LogUtils.addError("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        try {
            LogUtils.addError("common", "$tag: $msg")
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun w(msg: String) {
        Log.w(TAG, msg)
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun i(msg: String) {
        Log.i(TAG, msg)
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun d(msg: String) {
        Log.d(TAG, msg)
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun v(msg: String) {
        Log.v(TAG, msg)
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        try {
            LogUtils.addRunLog("common", msg)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    // ========== long ==========
    fun e(value: Long) {
        e(value.toString())
    }

    fun e(tag: String?, value: Long) {
        e(tag, value.toString())
    }

    fun w(value: Long) {
        w(value.toString())
    }

    fun w(tag: String?, value: Long) {
        w(tag, value.toString())
    }

    fun i(value: Long) {
        i(value.toString())
    }

    fun i(tag: String?, value: Long) {
        i(tag, value.toString())
    }

    fun d(value: Long) {
        d(value.toString())
    }

    fun d(tag: String?, value: Long) {
        d(tag, value.toString())
    }

    fun v(value: Long) {
        v(value.toString())
    }

    fun v(tag: String?, value: Long) {
        v(tag, value.toString())
    }

    // ========== Throwable ==========
    @JvmStatic
    fun e(e: Throwable) {
        Log.e(TAG, e.toString(), e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun w(e: Throwable) {
        Log.w(TAG, e.toString(), e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun i(e: Throwable) {
        Log.i(TAG, e.toString(), e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun i(e: Throwable, output: Boolean) {
        Log.i(TAG, e.toString(), e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
        if (output) {
            XposedBridge.log(e)
        }
    }

    fun d(e: Throwable) {
        Log.d(TAG, e.toString(), e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    // ========== Tag + String + Throwable ==========
    @JvmStatic
    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    // ========== String + Throwable ==========
    @JvmStatic
    fun e(msg: String, e: Throwable) {
        Log.e(TAG, msg, e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    @JvmStatic
    fun w(msg: String, e: Throwable) {
        Log.w(TAG, msg, e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun i(msg: String, e: Throwable) {
        Log.i(TAG, msg, e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    fun d(msg: String, e: Throwable) {
        Log.d(TAG, msg, e)
        try {
            LogUtils.addError("common", e)
        } catch (error: ExceptionInInitializerError) {
            Log.e(BuildConfig.TAG, "common", error)
        } catch (error: NoClassDefFoundError) {
            Log.e(BuildConfig.TAG, "common", error)
        }
    }

    // ========== 堆栈打印 ==========
    private fun log(logLevel: Int, tag: String, msg: String) {
        when (logLevel) {
            Log.VERBOSE -> Log.v(tag, msg)
            Log.DEBUG -> Log.d(tag, msg)
            Log.INFO -> Log.i(tag, msg)
            Log.WARN -> Log.w(tag, msg)
            Log.ERROR -> Log.e(tag, msg)
            else -> throw IllegalArgumentException("Invalid log level: $logLevel")
        }
    }

    /**
     * 打印当前调用堆栈 DEBUG
     */
    fun printStackTrace() {
        printStackTrace(Log.DEBUG, TAG, "Current Stack Trace:")
    }

    /**
     * 打印当前调用堆栈
     * 
     * @param logLevel 日志级别（Log.VERBOSE/DEBUG/INFO/WARN/ERROR）
     */
    fun printStackTrace(logLevel: Int) {
        printStackTrace(logLevel, TAG, "Current Stack Trace:")
    }

    /**
     * 打印当前调用堆栈
     * 
     * @param logLevel 日志级别
     * @param tag      自定义TAG
     * @param prefix   堆栈信息前缀
     */
    @SuppressLint("DefaultLocale")
    fun printStackTrace(logLevel: Int, tag: String, prefix: String) {
        log(logLevel, tag, getStackTraceString())
    }

    fun printStackTraceErr(tag: String, th: Throwable) {
        e(tag, Log.getStackTraceString(th))
    }

    @JvmStatic
    @SuppressLint("DefaultLocale")
    fun getStackTraceString(): String {
        val stackTrace =
            Thread.currentThread().stackTrace

        val stackTraceMsg = StringBuilder().append("\n")
        var startRecording = false

        for (element in stackTrace) {
            val className = element!!.className
            if (className.contains("LSPHooker")) {
                startRecording = true
                continue
            }

            // 如果还没遇到目标类，直接跳过
            if (!startRecording) {
                continue
            }

            // 过滤掉无关紧要的系统类或当前类（可选）
            if (className == Thread::class.java.name) {
                continue
            }

            stackTraceMsg.append(
                String.format(
                    "  at %s.%s(%s:%d)\n",
                    element.className,
                    element.methodName,
                    element.fileName,
                    element.lineNumber
                )
            )
        }
        return stackTraceMsg.toString()
    }

    // ========== 分段打印 ==========
    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, BuildConfig.TAG, "[$tag]$msg")
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            Log.println(
                priority,
                BuildConfig.TAG,
                ("[" + tag + "]" + "[chunked] too long (" + len + " chars, " + chunkCount
                        + " chunks). head:\n" + head)
            )
            Log.println(
                priority,
                BuildConfig.TAG,
                "[$tag][chunked] truncated. Consider writing to file for full dump."
            )
            return
        }

        var i = 0
        var part = 1
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            Log.println(
                priority,
                BuildConfig.TAG,
                "[$tag][part $part/$chunkCount] $chunk"
            )
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) {
        logChunked(Log.INFO, tag, msg)
    }

    fun logChunkedE(tag: String, msg: String) {
        logChunked(Log.ERROR, tag, msg)
    }

    fun logChunkedW(tag: String, msg: String) {
        logChunked(Log.WARN, tag, msg)
    }

    fun logChunkedD(tag: String, msg: String) {
        logChunked(Log.DEBUG, tag, msg)
    }
}