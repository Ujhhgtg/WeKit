package moe.ouom.wekit.utils.common;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import moe.ouom.wekit.host.impl.HostInfo;
import moe.ouom.wekit.utils.log.WeLogger;

@SuppressLint("PrivateApi")
public class SyncUtils {
    /**
     * static {
     * String pkgName = v2.f219924b;
     * boolean zF = vf.a.f("clicfg_matrix_lifecycle_process_supervisor_enable", true);
     * o.f(pkgName, "pkgName");
     * f260937a = new q(new j0(zF, false, c0.d(pkgName, pkgName.concat(":push"), pkgName.concat(":tools"), pkgName.concat(":appbrand0"), pkgName.concat(":appbrand1"), pkgName.concat(":appbrand2"), pkgName.concat(":appbrand3"), pkgName.concat(":appbrand4"), pkgName.concat(":sandbox"), pkgName.concat(":exdevice"), pkgName.concat(":support"), pkgName.concat(":cuploader"), pkgName.concat(":patch"), pkgName.concat(":hotpot.."), pkgName.concat(":fallback"), pkgName.concat(":dexopt"), pkgName.concat(":recovery"), pkgName.concat(":nospace"), pkgName.concat(":jectl"), pkgName.concat(":opengl_detector"), pkgName.concat(":rubbishbin"), pkgName.concat(":isolated_process0"), pkgName.concat(":isolated_process1"), pkgName.concat(":res_can_worker"), pkgName.concat(":extmig"), pkgName.concat(":backtrace__"), pkgName.concat(":TMAssistantDownloadSDKService"), pkgName.concat(":switch"), pkgName.concat(":hld"), pkgName.concat(":playcore_missing_splits_activity"), pkgName.concat(":hldfl"), pkgName.concat(":magic_emoji"))), true, true, new p(!((Boolean) ((n) h.a(a.f260933d)).getValue()).booleanValue() ? null : new b(new LinkedBlockingQueue(), new kk5.d()), 5, 30L, c.f260934d, d.f260935d), true);
     * }
     * }
     */
    // 微信的进程常量
    public static final int PROC_MAIN = 1;              // com.tencent.mm
    public static final int PROC_PUSH = 1 << 1;         // :push
    public static final int PROC_APPBRAND = 1 << 2;     // :appbrand0~4
    public static final int PROC_TOOLS = 1 << 3;        // :tools, :toolsmp
    public static final int PROC_SANDBOX = 1 << 4;      // :sandbox
    public static final int PROC_HOTPOT = 1 << 5;       // :hotpot..
    public static final int PROC_EXDEVICE = 1 << 6;     // :exdevice
    public static final int PROC_SUPPORT = 1 << 7;      // :support
    public static final int PROC_CUPLOADER = 1 << 8;    // :cuploader
    public static final int PROC_PATCH = 1 << 9;        // :patch
    public static final int PROC_FALLBACK = 1 << 10;    // :fallback
    public static final int PROC_DEXOPT = 1 << 11;      // :dexopt
    public static final int PROC_RECOVERY = 1 << 12;    // :recovery
    public static final int PROC_NOSPACE = 1 << 13;     // :nospace
    public static final int PROC_JECTL = 1 << 14;       // :jectl
    public static final int PROC_OPENGL_DETECTOR = 1 << 15;  // :opengl_detector
    public static final int PROC_RUBBISHBIN = 1 << 16;  // :rubbishbin
    public static final int PROC_ISOLATED = 1 << 17;    // :isolated_process0, :isolated_process1
    public static final int PROC_RES_CAN_WORKER = 1 << 18;  // :res_can_worker
    public static final int PROC_EXTMIG = 1 << 19;      // :extmig
    public static final int PROC_BACKTRACE = 1 << 20;   // :backtrace__
    public static final int PROC_TMASSISTANT = 1 << 21; // :TMAssistantDownloadSDKService
    public static final int PROC_SWITCH = 1 << 22;      // :switch
    public static final int PROC_HLD = 1 << 23;         // :hld
    public static final int PROC_PLAYCORE = 1 << 24;    // :playcore_missing_splits_activity
    public static final int PROC_HLDFL = 1 << 25;       // :hldfl
    public static final int PROC_MAGIC_EMOJI = 1 << 26; // :magic_emoji

    public static final int PROC_OTHERS = 1 << 30;      // 其他未知进程

    private static int mProcType = 0;
    private static String mProcName = null;
    private static Handler sHandler;
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    private SyncUtils() {
        throw new AssertionError("No instance for you!");
    }

    /**
     * 获取当前进程类型
     */
    public static int getProcessType() {
        if (mProcType != 0) {
            return mProcType;
        }

        var procName = getProcessName();
        var parts = procName.split(":");

        if (parts.length == 1) {
            mProcType = PROC_MAIN;
        } else {
            var tail = parts[parts.length - 1];

            // 按照进程名称匹配
            if ("push".equals(tail)) {
                mProcType = PROC_PUSH;
            } else if (tail.startsWith("appbrand")) {
                mProcType = PROC_APPBRAND;
            } else if (tail.startsWith("tools")) {
                mProcType = PROC_TOOLS;
            } else if ("sandbox".equals(tail)) {
                mProcType = PROC_SANDBOX;
            } else if (tail.startsWith("hotpot")) {
                mProcType = PROC_HOTPOT;
            } else if ("exdevice".equals(tail)) {
                mProcType = PROC_EXDEVICE;
            } else if ("support".equals(tail)) {
                mProcType = PROC_SUPPORT;
            } else if ("cuploader".equals(tail)) {
                mProcType = PROC_CUPLOADER;
            } else if ("patch".equals(tail)) {
                mProcType = PROC_PATCH;
            } else if ("fallback".equals(tail)) {
                mProcType = PROC_FALLBACK;
            } else if ("dexopt".equals(tail)) {
                mProcType = PROC_DEXOPT;
            } else if ("recovery".equals(tail)) {
                mProcType = PROC_RECOVERY;
            } else if ("nospace".equals(tail)) {
                mProcType = PROC_NOSPACE;
            } else if ("jectl".equals(tail)) {
                mProcType = PROC_JECTL;
            } else if ("opengl_detector".equals(tail)) {
                mProcType = PROC_OPENGL_DETECTOR;
            } else if ("rubbishbin".equals(tail)) {
                mProcType = PROC_RUBBISHBIN;
            } else if (tail.startsWith("isolated_process")) {
                mProcType = PROC_ISOLATED;
            } else if ("res_can_worker".equals(tail)) {
                mProcType = PROC_RES_CAN_WORKER;
            } else if ("extmig".equals(tail)) {
                mProcType = PROC_EXTMIG;
            } else if (tail.startsWith("backtrace")) {
                mProcType = PROC_BACKTRACE;
            } else if ("TMAssistantDownloadSDKService".equals(tail)) {
                mProcType = PROC_TMASSISTANT;
            } else if ("switch".equals(tail)) {
                mProcType = PROC_SWITCH;
            } else if ("hld".equals(tail)) {
                mProcType = PROC_HLD;
            } else if ("playcore_missing_splits_activity".equals(tail)) {
                mProcType = PROC_PLAYCORE;
            } else if ("hldfl".equals(tail)) {
                mProcType = PROC_HLDFL;
            } else if ("magic_emoji".equals(tail)) {
                mProcType = PROC_MAGIC_EMOJI;
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
        var name = "unknown";
        var retry = 0;
        do {
            try {
                Context context = HostInfo.getHostInfo().getApplication();

                var runningAppProcesses =
                        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                                .getRunningAppProcesses();
                if (runningAppProcesses != null) {
                    for (var runningAppProcessInfo : runningAppProcesses) {
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