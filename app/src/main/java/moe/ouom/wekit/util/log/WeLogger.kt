package moe.ouom.wekit.util.log;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XposedBridge;
import moe.ouom.wekit.BuildConfig;

public class WeLogger {
    private WeLogger() {
    }

    private static final String TAG = BuildConfig.TAG;

    private static final int CHUNK_SIZE = 4000;
    private static final int MAX_CHUNKS = 200;

    // ========== String ==========
    public static void e(@NonNull String msg) {
        android.util.Log.e(TAG, msg);
        try {
            LogUtils.addError("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void e(String tag, @NonNull String msg) {
        android.util.Log.e(TAG, tag + ": " + msg);
        try {
            LogUtils.addError("common", tag + ": " + msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void w(@NonNull String msg) {
        android.util.Log.w(TAG, msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void w(String tag, @NonNull String msg) {
        android.util.Log.w(TAG, tag + ": " + msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(@NonNull String msg) {
        android.util.Log.i(TAG, msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(String tag, @NonNull String msg) {
        android.util.Log.i(TAG, tag + ": " + msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void d(@NonNull String msg) {
        android.util.Log.d(TAG, msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }

    }

    public static void d(String tag, @NonNull String msg) {
        android.util.Log.d(TAG, tag + ": " + msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void v(@NonNull String msg) {
        android.util.Log.v(TAG, msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void v(String tag, @NonNull String msg) {
        android.util.Log.v(TAG, tag + ": " + msg);
        try {
            LogUtils.addRunLog("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    // ========== long ==========
    public static void e(long value) {
        e(String.valueOf(value));
    }

    public static void e(String tag, long value) {
        e(tag, String.valueOf(value));
    }

    public static void w(long value) {
        w(String.valueOf(value));
    }

    public static void w(String tag, long value) {
        w(tag, String.valueOf(value));
    }

    public static void i(long value) {
        i(String.valueOf(value));
    }

    public static void i(String tag, long value) {
        i(tag, String.valueOf(value));
    }

    public static void d(long value) {
        d(String.valueOf(value));
    }

    public static void d(String tag, long value) {
        d(tag, String.valueOf(value));
    }

    public static void v(long value) {
        v(String.valueOf(value));
    }

    public static void v(String tag, long value) {
        v(tag, String.valueOf(value));
    }

    // ========== Throwable ==========
    public static void e(@NonNull Throwable e) {
        android.util.Log.e(TAG, e.toString(), e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void w(@NonNull Throwable e) {
        android.util.Log.w(TAG, e.toString(), e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(@NonNull Throwable e) {
        android.util.Log.i(TAG, e.toString(), e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(@NonNull Throwable e, boolean output) {
        android.util.Log.i(TAG, e.toString(), e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
        if (output) {
            XposedBridge.log(e);
        }
    }

    public static void d(@NonNull Throwable e) {
        android.util.Log.d(TAG, e.toString(), e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    // ========== Tag + String + Throwable ==========

    public static void e(String tag, @NonNull String msg, @NonNull Throwable e) {
        android.util.Log.e(TAG, tag + ": " + msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void w(String tag, @NonNull String msg, @NonNull Throwable e) {
        android.util.Log.w(TAG, tag + ": " + msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(String tag, @NonNull String msg, @NonNull Throwable e) {
        android.util.Log.i(TAG, tag + ": " + msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void d(String tag, @NonNull String msg, @NonNull Throwable e) {
        android.util.Log.d(TAG, tag + ": " + msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void v(String tag, @NonNull String msg, @NonNull Throwable e) {
        android.util.Log.v(TAG, tag + ": " + msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    // ========== String + Throwable ==========

    public static void e(@NonNull String msg, @NonNull Throwable e) {
        android.util.Log.e(TAG, msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void w(@NonNull String msg, @NonNull Throwable e) {
        android.util.Log.w(TAG, msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void i(@NonNull String msg, @NonNull Throwable e) {
        android.util.Log.i(TAG, msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void d(@NonNull String msg, @NonNull Throwable e) {
        android.util.Log.d(TAG, msg, e);
        try {
            LogUtils.addError("common", e);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    // ========== 堆栈打印 ==========
    private static void log(int logLevel, @NonNull String tag, @NonNull String msg) {
        switch (logLevel) {
            case Log.VERBOSE:
                Log.v(tag, msg);
                break;
            case Log.DEBUG:
                Log.d(tag, msg);
                break;
            case Log.INFO:
                Log.i(tag, msg);
                break;
            case Log.WARN:
                Log.w(tag, msg);
                break;
            case Log.ERROR:
                Log.e(tag, msg);
                break;
            default:
                throw new IllegalArgumentException("Invalid log level: " + logLevel);
        }
    }

    /**
     * 打印当前调用堆栈 DEBUG
     */
    public static void printStackTrace() {
        printStackTrace(Log.DEBUG, TAG, "Current Stack Trace:");
    }

    /**
     * 打印当前调用堆栈
     *
     * @param logLevel 日志级别（Log.VERBOSE/DEBUG/INFO/WARN/ERROR）
     */
    public static void printStackTrace(int logLevel) {
        printStackTrace(logLevel, TAG, "Current Stack Trace:");
    }

    /**
     * 打印当前调用堆栈
     *
     * @param logLevel 日志级别
     * @param tag      自定义TAG
     * @param prefix   堆栈信息前缀
     */
    @SuppressLint("DefaultLocale")
    public static void printStackTrace(int logLevel, @NonNull String tag, @NonNull String prefix) {
        log(logLevel, tag, getStackTraceString());
    }

    @NonNull
    public static void printStackTraceErr(@NonNull String TAG, @NonNull Throwable th) {
        e(TAG, android.util.Log.getStackTraceString(th));
    }

    @SuppressLint("DefaultLocale")
    @NonNull
    public static String getStackTraceString() {
        var stackTrace = Thread.currentThread().getStackTrace();

        var stackTraceMsg = new StringBuilder().append("\n");
        var startRecording = false;

        for (var element : stackTrace) {
            var className = element.getClassName();
            if (className.contains("LSPHooker")) {
                startRecording = true;
                continue;
            }

            // 如果还没遇到目标类，直接跳过
            if (!startRecording) {
                continue;
            }

            // 过滤掉无关紧要的系统类或当前类（可选）
            if (className.equals(Thread.class.getName())) {
                continue;
            }

            stackTraceMsg.append(String.format(
                    "  at %s.%s(%s:%d)\n",
                    element.getClassName(),
                    element.getMethodName(),
                    element.getFileName(),
                    element.getLineNumber()
            ));
        }
        return stackTraceMsg.toString();
    }

    // ========== 分段打印 ==========

    public static void logChunked(int priority, @NonNull String tag, @NonNull String msg) {
        if (msg.length() <= CHUNK_SIZE) {
            Log.println(priority, BuildConfig.TAG, "[" + tag + "]" + msg);
            return;
        }

        var len = msg.length();
        var chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (chunkCount > MAX_CHUNKS) {
            var head = msg.substring(0, Math.min(len, CHUNK_SIZE));
            Log.println(priority, BuildConfig.TAG, "[" + tag + "]" + "[chunked] too long (" + len + " chars, " + chunkCount
                    + " chunks). head:\n" + head);
            Log.println(priority, BuildConfig.TAG, "[" + tag + "]" + "[chunked] truncated. Consider writing to file for full dump.");
            return;
        }

        for (int i = 0, part = 1; i < len; i += CHUNK_SIZE, part++) {
            var end = Math.min(i + CHUNK_SIZE, len);
            var chunk = msg.substring(i, end);
            Log.println(priority, BuildConfig.TAG, "[" + tag + "]" + "[part " + part + "/" + chunkCount + "] " + chunk);
        }
    }

    public static void logChunkedI(@NonNull String tag, @NonNull String msg) {
        logChunked(Log.INFO, tag, msg);
    }

    public static void logChunkedE(@NonNull String tag, @NonNull String msg) {
        logChunked(Log.ERROR, tag, msg);
    }

    public static void logChunkedW(@NonNull String tag, @NonNull String msg) {
        logChunked(Log.WARN, tag, msg);
    }

    public static void logChunkedD(@NonNull String tag, @NonNull String msg) {
        logChunked(Log.DEBUG, tag, msg);
    }
}