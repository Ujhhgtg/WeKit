package moe.ouom.wekit.util.common;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import moe.ouom.wekit.host.impl.HostInfo;
import moe.ouom.wekit.util.log.WeLogger;

@SuppressLint("PrivateApi")
public class SyncUtils {
    // 微信的进程常量
    public static final int PROC_MAIN = 1;          // 主进程 (com.tencent.mm)
    public static final int PROC_PUSH = 1 << 1;     // 推送进程 (:push)
    public static final int PROC_APPBRAND = 1 << 2; // 小程序进程 (:appbrand0, :appbrand1 ...)
    public static final int PROC_TOOLS = 1 << 3;    // 工具进程 (:tools)
    public static final int PROC_SANDBOX = 1 << 4;  // 沙箱进程 (:sandbox)
    public static final int PROC_HOTPOT = 1 << 5;  // 不知道是什么，好像是开红包发现的 (:hotpot)

    public static final int PROC_OTHERS = 1 << 31;

    private static int mProcType = 0;
    private static String mProcName = null;
    private static Handler sHandler;
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    private SyncUtils() {
        throw new AssertionError("No instance for you!");
    }

    /**
     * 获取当前进程类型（适配微信）
     */
    public static int getProcessType() {
        if (mProcType != 0) {
            return mProcType;
        }

        String procName = getProcessName();
        // 你的日志里主进程是 "com.tencent.mm"，没有冒号
        String[] parts = procName.split(":");

        if (parts.length == 1) {
            mProcType = PROC_MAIN;
        } else {
            String tail = parts[parts.length - 1];

            if ("push".equals(tail)) {
                mProcType = PROC_PUSH;
            } else if (tail.startsWith("appbrand")) {
                mProcType = PROC_APPBRAND;
            } else if (tail.startsWith("tools")) {
                // 适配 :tools, :toolsmp
                mProcType = PROC_TOOLS;
            } else if ("sandbox".equals(tail)) {
                mProcType = PROC_SANDBOX;
            } else if ("hotpot".equals(tail)) {
                mProcType = PROC_HOTPOT;
            } else {
                mProcType = PROC_OTHERS;
            }
        }
        return mProcType;
    }

    public static boolean isMainProcess() {
        return getProcessType() == PROC_MAIN;
    }

    public static boolean isTargetProcess(int target) {
        return (getProcessType() & target) != 0;
    }

    public static String getProcessName() {
        if (mProcName != null) {
            return mProcName;
        }
        String name = "unknown";
        int retry = 0;
        do {
            try {
                Context context = HostInfo.getHostInfo().getApplication();

                List<ActivityManager.RunningAppProcessInfo> runningAppProcesses =
                        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                                .getRunningAppProcesses();
                if (runningAppProcesses != null) {
                    for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcesses) {
                        if (runningAppProcessInfo != null && runningAppProcessInfo.pid == android.os.Process.myPid()) {
                            mProcName = runningAppProcessInfo.processName;
                            return runningAppProcessInfo.processName;
                        }
                    }
                }
            } catch (Throwable e) {
                WeLogger.e("getProcessName error " + e);
            }
            retry++;
            if (retry >= 3) {
                break;
            }
        } while ("unknown".equals(name));
        return name;
    }

    public static void runOnUiThread(@NonNull Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            post(r);
        }
    }

    public static void async(@NonNull Runnable r) {
        sExecutor.execute(r);
    }

    @SuppressLint("LambdaLast")
    public static void postDelayed(@NonNull Runnable r, long ms) {
        if (sHandler == null) {
            sHandler = new Handler(Looper.getMainLooper());
        }
        sHandler.postDelayed(r, ms);
    }

    public static void postDelayed(long ms, @NonNull Runnable r) {
        postDelayed(r, ms);
    }

    public static void post(@NonNull Runnable r) {
        postDelayed(r, 0L);
    }

    public static void requiresUiThread() {
        requiresUiThread(null);
    }

    public static void requiresUiThread(@Nullable String msg) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(msg == null ? "UI thread required" : msg);
        }
    }

    public static void requiresNonUiThread() {
        requiresNonUiThread(null);
    }

    public static void requiresNonUiThread(@Nullable String msg) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(msg == null ? "non-UI thread required" : msg);
        }
    }
}