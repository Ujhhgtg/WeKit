package moe.ouom.wekit.util.log;

import static moe.ouom.wekit.constants.Constants.PrekEnableLog;

import android.annotation.SuppressLint;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import de.robv.android.xposed.XposedBridge;
import moe.ouom.wekit.config.WeConfig;
import moe.ouom.wekit.loader.core.NativeCoreBridge;
import moe.ouom.wekit.util.io.FileUtils;
import moe.ouom.wekit.util.io.PathTool;


public class LogUtils {
    private static String getLogRootDirectory() {
        try {
            return PathTool.getModuleDataPath() + "/log/";
        } catch (Exception e) {
            // HostInfo not initialized yet, return null to skip logging
            return null;
        }
    }

    private static String getRunLogDirectory() {
        var root = getLogRootDirectory();
        return root != null ? root + "RunLog" + File.separator : null;
    }

    private static String getErrorLogDirectory() {
        var root = getLogRootDirectory();
        return root != null ? root + "ErrorLog" + File.separator : null;
    }


    /**
     * @return 获取调用此方法的调用栈
     */
    public static String getCallStack() {
        var throwable = new Throwable();
        return getStackTrace(throwable);
    }

    /**
     * 获取堆栈跟踪
     *
     * @param throwable new Throwable || Exception
     * @return 堆栈跟踪
     */
    public static String getStackTrace(Throwable throwable) {
        var result = new StringBuilder();
        result.append(throwable).append("\n");
        var stackTraceElements = throwable.getStackTrace();
        for (var stackTraceElement : stackTraceElements) {
            //不把当前类加入结果中
            if (stackTraceElement.getClassName().equals(LogUtils.class.getName())) continue;
            result.append(stackTraceElement).append("\n");
        }
        return result.toString();
    }

    public static void addError(Throwable e) {
        addError("Error Log", e.toString(), e);
    }

    public static void addRunLog(Object content) {
        addRunLog("Run Log", content);
    }

    /**
     * 记录运行日志 确保能走到那一行代码
     *
     * @param TAG(文件名) 内容
     */
    public static void addRunLog(String TAG, Object content) {
        addLog(TAG, String.valueOf(content), content, false);
    }

    /**
     * 记录异常
     */
    public static void addError(String TAG, Throwable e) {
        addLog(TAG, e.toString(), e, true);
    }

    /**
     * 记录异常
     *
     * @param TAG         (标签 文件名)
     * @param Description 错误的相关描述
     * @param e           Exception
     */
    public static void addError(String TAG, String Description, Throwable e) {
        addLog(TAG, Description, e, true);
    }


    private static void addLog(String fileName, String Description, Object content, boolean isError) {
        try {
            if (NativeCoreBridge.isNativeCoreInitialized() && !WeConfig.getDefaultConfig().getBooleanOrFalse(PrekEnableLog)) {
                return;
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }

        var directory = isError ? getErrorLogDirectory() : getRunLogDirectory();
        if (directory == null) {
            // HostInfo not initialized yet, skip file logging
            return;
        }

        var path = directory + fileName + ".log";
        var stringBuffer = new StringBuilder(getTime());
        stringBuffer.append("\n").append(Description);
        if (content instanceof Exception) {
            stringBuffer.append("\n").append(getStackTrace((Exception) content));
        }
        stringBuffer.append("\n\n");
        FileUtils.writeTextToFile(path, stringBuffer.toString(), true);
    }

    public static String getTime() {
        @SuppressLint("SimpleDateFormat") var df = new SimpleDateFormat("[yyyy/MM/dd HH:mm:ss]");
        var calendar = Calendar.getInstance();
        return df.format(calendar.getTime());
    }

    public static void addError(String TAG, String msg) {
        addLog(TAG, msg, null, true);
    }
}
