package moe.ouom.wekit.utils.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import moe.ouom.wekit.utils.log.WeLogger
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃信息收集工具类
 * 负责收集设备信息、应用信息、线程信息、堆栈信息等
 * 
 * @author cwuom
 * @since 1.0.0
 */
object CrashInfoCollector {
    /**
     * 收集完整的崩溃信息
     * 
     * @param context   上下文
     * @param throwable 异常对象
     * @param crashType 崩溃类型 (JAVA/NATIVE)
     * @return 格式化的崩溃信息
     */
    @JvmStatic
    fun collectCrashInfo(context: Context, throwable: Throwable, crashType: String): String {
        // 崩溃头部信息

        val sb = "========================================\n" +
                "WeKit Crash Report\n" +
                "========================================\n\n" +  // 崩溃时间
                "Crash Time: " + currentTime + "\n" +
                "Crash Type: " + crashType + "\n\n" +  // 设备信息
                "========================================\n" +
                "Device Information\n" +
                "========================================\n" +
                collectDeviceInfo() + "\n" +  // 应用信息
                "========================================\n" +
                "Application Information\n" +
                "========================================\n" +
                collectAppInfo(context) + "\n" +  // 内存信息
                "========================================\n" +
                "Memory Information\n" +
                "========================================\n" +
                collectMemoryInfo(context) + "\n" +  // 线程信息
                "========================================\n" +
                "Thread Information\n" +
                "========================================\n" +
                collectThreadInfo() + "\n" +  // 异常堆栈
                "========================================\n" +
                "Exception Stack Trace\n" +
                "========================================\n" +
                getStackTraceString(throwable) + "\n" +  // 所有线程堆栈
                "========================================\n" +
                "All Threads Stack Trace\n" +
                "========================================\n" +
                collectAllThreadsStackTrace() + "\n" +
                "========================================\n" +
                "End of Crash Report\n" +
                "========================================\n"

        return sb
    }

    private val currentTime: String
        /**
         * 获取当前时间
         */
        get() {
            val sdf = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
            )
            return sdf.format(Date())
        }

    /**
     * 收集设备信息
     */
    private fun collectDeviceInfo(): String {
        val sb = StringBuilder()
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Product: ").append(Build.PRODUCT).append("\n")
        sb.append("Hardware: ").append(Build.HARDWARE).append("\n")
        sb.append("Board: ").append(Build.BOARD).append("\n")
        sb.append("Display: ").append(Build.DISPLAY).append("\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
        sb.append("Android Version: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK Version: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Supported ABIs: ")
        for (abi in Build.SUPPORTED_ABIS) {
            sb.append(abi).append(" ")
        }
        sb.append("\n")
        return sb.toString()
    }

    /**
     * 收集应用信息
     */
    private fun collectAppInfo(context: Context): String {
        val sb = StringBuilder()
        try {
            val packageInfo =
                context.packageManager.getPackageInfo(context.packageName, 0)
            sb.append("Package Name: ").append(packageInfo.packageName).append("\n")
            sb.append("Version Name: ").append(packageInfo.versionName).append("\n")
            sb.append("Version Code: ").append(packageInfo.longVersionCode).append("\n")
            sb.append("Process ID: ").append(Process.myPid()).append("\n")
            sb.append("Thread ID: ").append(Thread.currentThread().id).append("\n")
            sb.append("Process Name: ").append(getProcessName(context)).append("\n")
        } catch (e: Exception) {
            sb.append("Failed to collect app info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 收集内存信息
     */
    private fun collectMemoryInfo(context: Context): String {
        val sb = StringBuilder()
        try {
            // 应用内存信息
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory

            sb.append("Max Memory: ").append(maxMemory).append(" MB\n")
            sb.append("Total Memory: ").append(totalMemory).append(" MB\n")
            sb.append("Used Memory: ").append(usedMemory).append(" MB\n")
            sb.append("Free Memory: ").append(freeMemory).append(" MB\n")

            // Native内存信息
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            sb.append("Native Heap Size: ").append(memoryInfo.nativePss).append(" KB\n")
            sb.append("Dalvik Heap Size: ").append(memoryInfo.dalvikPss).append(" KB\n")
            sb.append("Total PSS: ").append(memoryInfo.totalPss).append(" KB\n")

            // 系统内存信息
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            if (activityManager != null) {
                val systemMemInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(systemMemInfo)
                sb.append("System Available Memory: ").append(systemMemInfo.availMem / 1024 / 1024)
                    .append(" MB\n")
                sb.append("System Total Memory: ").append(systemMemInfo.totalMem / 1024 / 1024)
                    .append(" MB\n")
                sb.append("System Low Memory: ").append(systemMemInfo.lowMemory).append("\n")
            }
        } catch (e: Exception) {
            sb.append("Failed to collect memory info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 收集线程信息
     */
    private fun collectThreadInfo(): String {
        val sb = StringBuilder()
        try {
            val currentThread = Thread.currentThread()
            sb.append("Current Thread: ").append(currentThread.name).append("\n")
            sb.append("Thread ID: ").append(currentThread.id).append("\n")
            sb.append("Thread Priority: ").append(currentThread.priority).append("\n")
            sb.append("Thread State: ").append(currentThread.state).append("\n")
            sb.append("Thread Group: ").append(currentThread.threadGroup!!.name ?: "null").append("\n")

            // 活跃线程数
            var rootGroup = Thread.currentThread().threadGroup!!
            while (rootGroup.parent != null) {
                rootGroup = rootGroup.parent
            }
            sb.append("Active Thread Count: ").append(rootGroup.activeCount()).append("\n")
        } catch (e: Exception) {
            sb.append("Failed to collect thread info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 获取异常堆栈字符串
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    /**
     * 收集所有线程的堆栈信息
     */
    private fun collectAllThreadsStackTrace(): String {
        val sb = StringBuilder()
        try {
            val allStackTraces = Thread.getAllStackTraces()
            sb.append("Total Threads: ").append(allStackTraces.size).append("\n\n")

            for (entry in allStackTraces.entries) {
                val thread = entry.key
                val stackTrace = entry.value

                sb.append("Thread: ").append(thread.name)
                    .append(" (ID: ").append(thread.id)
                    .append(", State: ").append(thread.state)
                    .append(", Priority: ").append(thread.priority)
                    .append(")\n")

                if (stackTrace != null && stackTrace.size > 0) {
                    for (element in stackTrace) {
                        sb.append("    at ").append(element.toString()).append("\n")
                    }
                } else {
                    sb.append("    (No stack trace available)\n")
                }
                sb.append("\n")
            }
        } catch (e: Exception) {
            sb.append("Failed to collect all threads stack trace: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 获取进程名称
     */
    private fun getProcessName(context: Context): String {
        try {
            val pid = Process.myPid()
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            if (activityManager != null) {
                for (processInfo in activityManager.runningAppProcesses) {
                    if (processInfo.pid == pid) {
                        return processInfo.processName
                    }
                }
            }

            // 备用方法：读取 /proc/self/cmdline
            val reader = BufferedReader(FileReader("/proc/self/cmdline"))
            var processName = reader.readLine()
            reader.close()
            if (processName != null) {
                processName = processName.trim { it <= ' ' }
                if (!processName.isEmpty()) {
                    return processName
                }
            }
        } catch (e: Exception) {
            WeLogger.e("error while getting process name", e)
        }
        return "Unknown"
    }
}
