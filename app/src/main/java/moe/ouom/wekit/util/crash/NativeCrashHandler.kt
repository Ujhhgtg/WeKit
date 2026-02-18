package moe.ouom.wekit.util.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import lombok.Getter;
import moe.ouom.wekit.util.log.WeLogger;

/**
 * Native 层崩溃拦截处理器
 * 通过 JNI 调用 Native 代码安装信号处理器
 *
 * @author cwuom
 * @since 1.0.0
 */
public class NativeCrashHandler {

    private final Context context;
    private final CrashLogManager crashLogManager;

    /**
     * -- GETTER --
     * 检查是否已安装
     */
    @Getter
    public boolean isInstalled = false;

    // Native 方法声明
    private native boolean installNative(String crashLogDir);

    private native void uninstallNative();

    private native void triggerTestCrashNative(int crashType);

    public NativeCrashHandler(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.crashLogManager = new CrashLogManager(this.context);
    }

    /**
     * 安装 Native 崩溃拦截器
     *
     * @return 是否安装成功
     */
    public boolean install() {
        if (isInstalled) {
            WeLogger.i("NativeCrashHandler", "Native crash handler already installed");
            return true;
        }

        try {
            var crashLogDir = crashLogManager.getCrashLogDirPath();
            var result = installNative(crashLogDir);

            if (result) {
                isInstalled = true;
                WeLogger.i("NativeCrashHandler", "Native crash handler installed successfully");
            } else {
                WeLogger.e("NativeCrashHandler", "Failed to install native crash handler");
            }

            return result;
        } catch (Throwable e) {
            WeLogger.e("[NativeCrashHandler] Failed to install native crash handler", e);
            return false;
        }
    }

    /**
     * 卸载 Native 崩溃拦截器
     */
    public void uninstall() {
        if (!isInstalled) {
            return;
        }

        try {
            uninstallNative();
            isInstalled = false;
            WeLogger.i("NativeCrashHandler", "Native crash handler uninstalled");
        } catch (Throwable e) {
            WeLogger.e("[NativeCrashHandler] Failed to uninstall native crash handler", e);
        }
    }

    /**
     * 触发测试崩溃
     *
     * @param crashType 崩溃类型
     *                  0 = SIGSEGV (空指针访问)
     *                  1 = SIGABRT (abort)
     *                  2 = SIGFPE (除零错误)
     *                  3 = SIGILL (非法指令)
     *                  4 = SIGBUS (总线错误)
     */
    public void triggerTestCrash(int crashType) {
        WeLogger.w("NativeCrashHandler", "Triggering test crash: type=" + crashType);
        try {
            triggerTestCrashNative(crashType);
        } catch (Throwable e) {
            WeLogger.e("[NativeCrashHandler] Failed to trigger test crash", e);
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
