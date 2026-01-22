package moe.ouom.wekit.util.log;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XposedBridge;
import moe.ouom.wekit.BuildConfig;

public class WeLogger {

    private WeLogger() {}

    private static final String TAG = BuildConfig.TAG;

    // ========== String==========
    public static void e(@NonNull String msg) {
        android.util.Log.e(TAG, msg);
        try {
            LogUtils.addError("common", msg);
        } catch (ExceptionInInitializerError | NoClassDefFoundError error) {
            Log.e(BuildConfig.TAG, "common", error);
        }
    }

    public static void e(String tag, @NonNull String msg) {
        android.util.Log.e(TAG, tag + ": "+ msg);
        try {
            LogUtils.addError("common", tag + ": "+ msg);
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
        android.util.Log.w(TAG, tag + ": "+ msg);
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
        android.util.Log.i(TAG, tag + ": "+ msg);
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
        android.util.Log.d(TAG, tag + ": "+ msg);
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
        android.util.Log.v(TAG, tag + ": "+ msg);
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
        if (output){
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
     * @param logLevel 日志级别（Log.VERBOSE/DEBUG/INFO/WARN/ERROR）
     */
    public static void printStackTrace(int logLevel) {
        printStackTrace(logLevel, TAG, "Current Stack Trace:");
    }

    /**
     * 打印当前调用堆栈
     * @param logLevel 日志级别
     * @param tag 自定义TAG
     * @param prefix 堆栈信息前缀
     */
    @SuppressLint("DefaultLocale")
    public static void printStackTrace(int logLevel, @NonNull String tag, @NonNull String prefix) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (stackTrace.length == 0) {
            log(logLevel, tag, prefix + " - Empty stack trace");
            return;
        }

        StringBuilder stackTraceMsg = new StringBuilder(prefix).append("\n");
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.equals(BuildConfig.APPLICATION_ID) || className.equals(Thread.class.getName())) {
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

        log(logLevel, tag, stackTraceMsg.toString());
    }

    @NonNull
    public static void printStackTraceErr(@NonNull String TAG, @NonNull Throwable th) {
        e(TAG, android.util.Log.getStackTraceString(th));
    }

    @NonNull
    public static String getStackTraceString(@NonNull Throwable th) {
        return android.util.Log.getStackTraceString(th);
    }
}