package moe.ouom.wekit.util.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import moe.ouom.wekit.util.log.WeLogger;

/**
 * Java 层崩溃拦截处理器
 * 实现 UncaughtExceptionHandler 接口，拦截未捕获的异常
 *
 * @author cwuom
 * @since 1.0.0
 */
public class JavaCrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final CrashLogManager crashLogManager;
    private boolean isHandling = false;

    public JavaCrashHandler(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.crashLogManager = new CrashLogManager(this.context);
    }

    /**
     * 安装崩溃拦截器
     */
    public void install() {
        Thread.setDefaultUncaughtExceptionHandler(this);
        WeLogger.i("JavaCrashHandler", "Java crash handler installed");
    }

    /**
     * 卸载崩溃拦截器
     */
    public void uninstall() {
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
            WeLogger.i("JavaCrashHandler", "Java crash handler uninstalled");
        }
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        // 防止递归调用
        if (isHandling) {
            WeLogger.e("JavaCrashHandler", "Recursive crash detected, delegating to default handler");
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
            return;
        }

        isHandling = true;

        try {
            WeLogger.e("JavaCrashHandler", "========================================");
            WeLogger.e("JavaCrashHandler", "Uncaught exception detected!");
            WeLogger.e("JavaCrashHandler", "Thread: " + thread.getName() + " (ID: " + thread.getId() + ")");
            WeLogger.e("JavaCrashHandler", "Exception: " + throwable.getClass().getName());
            WeLogger.e("JavaCrashHandler", "Message: " + throwable.getMessage());
            WeLogger.e("JavaCrashHandler", "========================================");

            // 收集崩溃信息
            String crashInfo = CrashInfoCollector.collectCrashInfo(context, throwable, "JAVA");

            // 保存崩溃日志
            String logPath = crashLogManager.saveCrashLog(crashInfo);
            if (logPath != null) {
                WeLogger.i("JavaCrashHandler", "Crash log saved to: " + logPath);
            } else {
                WeLogger.e("JavaCrashHandler", "Failed to save crash log");
            }

            // 使用WeLogger记录崩溃
            WeLogger.e("[JavaCrashHandler] Crash details", throwable);

        } catch (Throwable e) {
            WeLogger.e("[JavaCrashHandler] Error while handling crash", e);
        } finally {
            isHandling = false;

            // 调用默认处理器，让应用正常崩溃
            if (defaultHandler != null) {
                WeLogger.i("JavaCrashHandler", "Delegating to default handler");
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                // 如果没有默认处理器，手动终止进程
                WeLogger.e("JavaCrashHandler", "No default handler, killing process");
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * 获取崩溃日志管理器
     *
     * @return 崩溃日志管理器
     */
    @NonNull
    public CrashLogManager getCrashLogManager() {
        return crashLogManager;
    }
}
