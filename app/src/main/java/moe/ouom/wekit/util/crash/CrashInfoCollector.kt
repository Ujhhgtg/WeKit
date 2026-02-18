package moe.ouom.wekit.util.crash;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃信息收集工具类
 * 负责收集设备信息、应用信息、线程信息、堆栈信息等
 *
 * @author cwuom
 * @since 1.0.0
 */
public class CrashInfoCollector {

    private CrashInfoCollector() {
    }

    /**
     * 收集完整的崩溃信息
     *
     * @param context   上下文
     * @param throwable 异常对象
     * @param crashType 崩溃类型 (JAVA/NATIVE)
     * @return 格式化的崩溃信息
     */
    @NonNull
    public static String collectCrashInfo(@NonNull Context context, @NonNull Throwable throwable, @NonNull String crashType) {

        // 崩溃头部信息

        String sb = "========================================\n" +
                "WeKit Crash Report\n" +
                "========================================\n\n" +

                // 崩溃时间
                "Crash Time: " + getCurrentTime() + "\n" +
                "Crash Type: " + crashType + "\n\n" +

                // 设备信息
                "========================================\n" +
                "Device Information\n" +
                "========================================\n" +
                collectDeviceInfo() + "\n" +

                // 应用信息
                "========================================\n" +
                "Application Information\n" +
                "========================================\n" +
                collectAppInfo(context) + "\n" +

                // 内存信息
                "========================================\n" +
                "Memory Information\n" +
                "========================================\n" +
                collectMemoryInfo(context) + "\n" +

                // 线程信息
                "========================================\n" +
                "Thread Information\n" +
                "========================================\n" +
                collectThreadInfo() + "\n" +

                // 异常堆栈
                "========================================\n" +
                "Exception Stack Trace\n" +
                "========================================\n" +
                getStackTraceString(throwable) + "\n" +

                // 所有线程堆栈
                "========================================\n" +
                "All Threads Stack Trace\n" +
                "========================================\n" +
                collectAllThreadsStackTrace() + "\n" +
                "========================================\n" +
                "End of Crash Report\n" +
                "========================================\n";

        return sb;
    }

    /**
     * 获取当前时间
     */
    @NonNull
    private static String getCurrentTime() {
        var sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 收集设备信息
     */
    @NonNull
    private static String collectDeviceInfo() {
        var sb = new StringBuilder();
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Device: ").append(Build.DEVICE).append("\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Product: ").append(Build.PRODUCT).append("\n");
        sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
        sb.append("Board: ").append(Build.BOARD).append("\n");
        sb.append("Display: ").append(Build.DISPLAY).append("\n");
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        sb.append("Android Version: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK Version: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("CPU ABI: ").append(Build.CPU_ABI).append("\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            sb.append("Supported ABIs: ");
            for (var abi : Build.SUPPORTED_ABIS) {
                sb.append(abi).append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 收集应用信息
     */
    @NonNull
    private static String collectAppInfo(@NonNull Context context) {
        var sb = new StringBuilder();
        try {
            var packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("Package Name: ").append(packageInfo.packageName).append("\n");
            sb.append("Version Name: ").append(packageInfo.versionName).append("\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sb.append("Version Code: ").append(packageInfo.getLongVersionCode()).append("\n");
            } else {
                sb.append("Version Code: ").append(packageInfo.versionCode).append("\n");
            }
            sb.append("Process ID: ").append(Process.myPid()).append("\n");
            sb.append("Thread ID: ").append(Thread.currentThread().getId()).append("\n");
            sb.append("Process Name: ").append(getProcessName(context)).append("\n");
        } catch (Exception e) {
            sb.append("Failed to collect app info: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 收集内存信息
     */
    @NonNull
    private static String collectMemoryInfo(@NonNull Context context) {
        var sb = new StringBuilder();
        try {
            // 应用内存信息
            var runtime = Runtime.getRuntime();
            var maxMemory = runtime.maxMemory() / 1024 / 1024;
            var totalMemory = runtime.totalMemory() / 1024 / 1024;
            var freeMemory = runtime.freeMemory() / 1024 / 1024;
            var usedMemory = totalMemory - freeMemory;

            sb.append("Max Memory: ").append(maxMemory).append(" MB\n");
            sb.append("Total Memory: ").append(totalMemory).append(" MB\n");
            sb.append("Used Memory: ").append(usedMemory).append(" MB\n");
            sb.append("Free Memory: ").append(freeMemory).append(" MB\n");

            // Native内存信息
            var memoryInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memoryInfo);
            sb.append("Native Heap Size: ").append(memoryInfo.nativePss).append(" KB\n");
            sb.append("Dalvik Heap Size: ").append(memoryInfo.dalvikPss).append(" KB\n");
            sb.append("Total PSS: ").append(memoryInfo.getTotalPss()).append(" KB\n");

            // 系统内存信息
            var activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                var systemMemInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(systemMemInfo);
                sb.append("System Available Memory: ").append(systemMemInfo.availMem / 1024 / 1024).append(" MB\n");
                sb.append("System Total Memory: ").append(systemMemInfo.totalMem / 1024 / 1024).append(" MB\n");
                sb.append("System Low Memory: ").append(systemMemInfo.lowMemory).append("\n");
            }
        } catch (Exception e) {
            sb.append("Failed to collect memory info: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 收集线程信息
     */
    @NonNull
    private static String collectThreadInfo() {
        var sb = new StringBuilder();
        try {
            var currentThread = Thread.currentThread();
            sb.append("Current Thread: ").append(currentThread.getName()).append("\n");
            sb.append("Thread ID: ").append(currentThread.getId()).append("\n");
            sb.append("Thread Priority: ").append(currentThread.getPriority()).append("\n");
            sb.append("Thread State: ").append(currentThread.getState()).append("\n");
            sb.append("Thread Group: ").append(currentThread.getThreadGroup() != null ? currentThread.getThreadGroup().getName() : "null").append("\n");

            // 活跃线程数
            var rootGroup = Thread.currentThread().getThreadGroup();
            while (rootGroup.getParent() != null) {
                rootGroup = rootGroup.getParent();
            }
            sb.append("Active Thread Count: ").append(rootGroup.activeCount()).append("\n");
        } catch (Exception e) {
            sb.append("Failed to collect thread info: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取异常堆栈字符串
     */
    @NonNull
    private static String getStackTraceString(@NonNull Throwable throwable) {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * 收集所有线程的堆栈信息
     */
    @NonNull
    private static String collectAllThreadsStackTrace() {
        var sb = new StringBuilder();
        try {
            var allStackTraces = Thread.getAllStackTraces();
            sb.append("Total Threads: ").append(allStackTraces.size()).append("\n\n");

            for (var entry : allStackTraces.entrySet()) {
                var thread = entry.getKey();
                var stackTrace = entry.getValue();

                sb.append("Thread: ").append(thread.getName())
                        .append(" (ID: ").append(thread.getId())
                        .append(", State: ").append(thread.getState())
                        .append(", Priority: ").append(thread.getPriority())
                        .append(")\n");

                if (stackTrace != null && stackTrace.length > 0) {
                    for (var element : stackTrace) {
                        sb.append("    at ").append(element.toString()).append("\n");
                    }
                } else {
                    sb.append("    (No stack trace available)\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("Failed to collect all threads stack trace: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取进程名称
     */
    @NonNull
    private static String getProcessName(@NonNull Context context) {
        try {
            var pid = Process.myPid();
            var activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                for (var processInfo : activityManager.getRunningAppProcesses()) {
                    if (processInfo.pid == pid) {
                        return processInfo.processName;
                    }
                }
            }

            // 备用方法：读取 /proc/self/cmdline
            var reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            var processName = reader.readLine();
            reader.close();
            if (processName != null) {
                processName = processName.trim();
                if (!processName.isEmpty()) {
                    return processName;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }
}
