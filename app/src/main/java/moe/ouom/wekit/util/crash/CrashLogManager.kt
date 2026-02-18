package moe.ouom.wekit.util.crash;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import moe.ouom.wekit.util.log.WeLogger;

/**
 * 崩溃日志管理类
 * 负责崩溃日志的存储、读取、删除等操作
 *
 * @author cwuom
 * @since 1.0.0
 */
public class CrashLogManager {

    private static final String CRASH_LOG_DIR = "crash_logs";
    private static final String CRASH_LOG_PREFIX = "crash_";
    private static final String CRASH_LOG_SUFFIX = ".log";
    private static final String PENDING_CRASH_FLAG = "pending_crash.flag";
    private static final String PENDING_JAVA_CRASH_FLAG = "pending_java_crash.flag";
    private static final int MAX_LOG_FILES = 50; // 最多保留 50 个崩溃日志
    private static final int MAX_LOG_CONTENT_SIZE = 30 * 1024; // 最大读取30KB

    private final File crashLogDir;

    public CrashLogManager(@NonNull Context context) {
        var context1 = context.getApplicationContext();
        this.crashLogDir = new File(context1.getFilesDir(), CRASH_LOG_DIR);
        ensureCrashLogDirExists();
    }

    /**
     * 确保崩溃日志目录存在
     */
    private void ensureCrashLogDirExists() {
        if (!crashLogDir.exists()) {
            if (crashLogDir.mkdirs()) {
                WeLogger.i("CrashLogManager", "Crash log directory created: " + crashLogDir.getAbsolutePath());
            } else {
                WeLogger.e("CrashLogManager", "Failed to create crash log directory");
            }
        }
    }

    /**
     * 保存崩溃日志
     *
     * @param crashInfo 崩溃信息
     * @return 保存的文件路径，失败返回null
     */
    @Nullable
    public String saveCrashLog(@NonNull String crashInfo) {
        return saveCrashLog(crashInfo, false);
    }

    /**
     * 保存崩溃日志
     *
     * @param crashInfo   崩溃信息
     * @param isJavaCrash 是否为Java崩溃（true=Java, false=Native）
     * @return 保存的文件路径，失败返回null
     */
    @Nullable
    public String saveCrashLog(@NonNull String crashInfo, boolean isJavaCrash) {
        try {
            ensureCrashLogDirExists();

            // 生成文件名：crash_yyyyMMdd_HHmmss_SSS.log
            var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault());
            var fileName = CRASH_LOG_PREFIX + sdf.format(new Date()) + CRASH_LOG_SUFFIX;
            var logFile = new File(crashLogDir, fileName);

            // 写入崩溃信息
            var writer = new FileWriter(logFile);
            writer.write(crashInfo);
            writer.flush();
            writer.close();

            WeLogger.i("CrashLogManager", "Crash log saved: " + logFile.getAbsolutePath());

            // 根据崩溃类型设置不同的待处理标记
            if (isJavaCrash) {
                setPendingJavaCrashFlag(logFile.getName());
            } else {
                setPendingCrashFlag(logFile.getName());
            }

            // 清理旧日志
            cleanOldLogs();

            return logFile.getAbsolutePath();
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to save crash log", e);
            return null;
        }
    }

    /**
     * 获取所有崩溃日志文件
     *
     * @return 崩溃日志文件列表，按时间倒序排列
     */
    @NonNull
    public List<File> getAllCrashLogs() {
        ensureCrashLogDirExists();

        var files = crashLogDir.listFiles((dir, name) ->
                name.startsWith(CRASH_LOG_PREFIX) && name.endsWith(CRASH_LOG_SUFFIX)
        );

        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        List<File> logFiles = new ArrayList<>(Arrays.asList(files));

        // 按修改时间倒序排列（最新的在前面）
        logFiles.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        return logFiles;
    }

    /**
     * 读取崩溃日志内容
     * （用于UI显示，会截断过长内容）
     *
     * @param logFile 日志文件
     * @return 日志内容，失败返回null
     */
    @Nullable
    public String readCrashLog(@NonNull File logFile) {
        try {
            if (!logFile.exists() || !logFile.isFile()) {
                return null;
            }

            var fileSize = logFile.length();

            // 如果文件太大，只读取前面部分并添加提示
            if (fileSize > MAX_LOG_CONTENT_SIZE) {
                WeLogger.w("CrashLogManager", "Crash log file is too large (" + fileSize + " bytes), reading first " + MAX_LOG_CONTENT_SIZE + " bytes");

                var fis = new java.io.FileInputStream(logFile);
                var buffer = new byte[MAX_LOG_CONTENT_SIZE];
                var bytesRead = fis.read(buffer);
                fis.close();

                var content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                return content + "\n\n========================================\n" +
                        "【提示】日志内容过长，此处仅展示部分内容。\n" +
                        "请点击「导出文件」以保存完整日志。\n" +
                        "========================================";
            }

            // 正常大小的文件，完整读取
            var fis = new java.io.FileInputStream(logFile);
            var buffer = new byte[(int) fileSize];
            fis.read(buffer);
            fis.close();

            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to read crash log", e);
            return null;
        }
    }

    /**
     * 读取完整的崩溃日志内容
     *
     * @param logFile 日志文件
     * @return 完整的日志内容，失败返回null
     */
    @Nullable
    public String readFullCrashLog(@NonNull File logFile) {
        try {
            if (!logFile.exists() || !logFile.isFile()) {
                return null;
            }

            var fileSize = logFile.length();
            WeLogger.d("CrashLogManager", "Reading full crash log, size: " + fileSize + " bytes");

            // 读取完整文件内容，不进行截断
            var fis = new java.io.FileInputStream(logFile);
            var buffer = new byte[(int) fileSize];
            fis.read(buffer);
            fis.close();

            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to read full crash log", e);
            return null;
        }
    }

    /**
     * 删除崩溃日志
     *
     * @param logFile 日志文件
     * @return 是否删除成功
     */
    public boolean deleteCrashLog(@NonNull File logFile) {
        if (logFile.exists() && logFile.delete()) {
            WeLogger.i("CrashLogManager", "Crash log deleted: " + logFile.getName());
            return true;
        }
        return false;
    }

    /**
     * 删除所有崩溃日志
     *
     * @return 删除的文件数量
     */
    public int deleteAllCrashLogs() {
        var logFiles = getAllCrashLogs();
        var count = 0;
        for (var file : logFiles) {
            if (deleteCrashLog(file)) {
                count++;
            }
        }
        clearPendingCrashFlag();
        WeLogger.i("CrashLogManager", "Deleted " + count + " crash logs");
        return count;
    }

    /**
     * 清理旧日志，保留最新的MAX_LOG_FILES个
     */
    private void cleanOldLogs() {
        var logFiles = getAllCrashLogs();
        if (logFiles.size() > MAX_LOG_FILES) {
            WeLogger.i("CrashLogManager", "Cleaning old crash logs, current count: " + logFiles.size());
            for (var i = MAX_LOG_FILES; i < logFiles.size(); i++) {
                deleteCrashLog(logFiles.get(i));
            }
        }
    }

    /**
     * 设置待处理崩溃标记
     *
     * @param logFileName 崩溃日志文件名
     */
    private void setPendingCrashFlag(@NonNull String logFileName) {
        try {
            var flagFile = new File(crashLogDir, PENDING_CRASH_FLAG);
            var writer = new FileWriter(flagFile);
            writer.write(logFileName);
            writer.flush();
            writer.close();
            WeLogger.d("CrashLogManager", "Pending crash flag set: " + logFileName);
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to set pending crash flag", e);
        }
    }

    /**
     * 获取待处理的崩溃日志文件名
     *
     * @return 崩溃日志文件名，如果没有则返回null
     */
    @Nullable
    public String getPendingCrashLogFileName() {
        try {
            var flagFile = new File(crashLogDir, PENDING_CRASH_FLAG);
            if (!flagFile.exists()) {
                return null;
            }

            var fis = new java.io.FileInputStream(flagFile);
            var buffer = new byte[(int) flagFile.length()];
            fis.read(buffer);
            fis.close();

            var fileName = new String(buffer, StandardCharsets.UTF_8).trim();
            WeLogger.d("CrashLogManager", "Pending crash log: " + fileName);
            return fileName;
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to get pending crash flag", e);
            return null;
        }
    }

    /**
     * 获取待处理的崩溃日志文件
     *
     * @return 崩溃日志文件，如果没有则返回 null
     */
    @Nullable
    public File getPendingCrashLogFile() {
        var fileName = getPendingCrashLogFileName();
        if (fileName == null) {
            return null;
        }

        var logFile = new File(crashLogDir, fileName);
        if (logFile.exists() && logFile.isFile()) {
            return logFile;
        }

        // 文件不存在，清除标记
        clearPendingCrashFlag();
        return null;
    }

    /**
     * 清除待处理崩溃标记
     */
    public void clearPendingCrashFlag() {
        var flagFile = new File(crashLogDir, PENDING_CRASH_FLAG);
        if (flagFile.exists() && flagFile.delete()) {
            WeLogger.d("CrashLogManager", "Pending crash flag cleared");
        }
    }

    /**
     * 检查是否有待处理的崩溃
     *
     * @return 是否有待处理的崩溃
     */
    public boolean hasPendingCrash() {
        return getPendingCrashLogFile() != null;
    }

    /**
     * 获取崩溃日志目录路径
     *
     * @return 崩溃日志目录路径
     */
    @NonNull
    public String getCrashLogDirPath() {
        return crashLogDir.getAbsolutePath();
    }

    /**
     * 获取崩溃日志数量
     *
     * @return 崩溃日志数量
     */
    public int getCrashLogCount() {
        return getAllCrashLogs().size();
    }

    // ==================== Java 崩溃专用方法 ====================

    /**
     * 设置待处理Java崩溃标记
     *
     * @param logFileName 崩溃日志文件名
     */
    public void setPendingJavaCrashFlag(@NonNull String logFileName) {
        try {
            var flagFile = new File(crashLogDir, PENDING_JAVA_CRASH_FLAG);
            var writer = new FileWriter(flagFile);
            writer.write(logFileName);
            writer.flush();
            writer.close();
            WeLogger.d("CrashLogManager", "Pending Java crash flag set: " + logFileName);
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to set pending Java crash flag", e);
        }
    }

    /**
     * 获取待处理的Java崩溃日志文件名
     *
     * @return 崩溃日志文件名，如果没有则返回null
     */
    @Nullable
    public String getPendingJavaCrashLogFileName() {
        try {
            var flagFile = new File(crashLogDir, PENDING_JAVA_CRASH_FLAG);
            if (!flagFile.exists()) {
                return null;
            }

            var fis = new java.io.FileInputStream(flagFile);
            var buffer = new byte[(int) flagFile.length()];
            fis.read(buffer);
            fis.close();

            var fileName = new String(buffer, StandardCharsets.UTF_8).trim();
            WeLogger.d("CrashLogManager", "Pending Java crash log: " + fileName);
            return fileName;
        } catch (IOException e) {
            WeLogger.e("[CrashLogManager] Failed to get pending Java crash flag", e);
            return null;
        }
    }

    /**
     * 获取待处理的Java崩溃日志文件
     *
     * @return 崩溃日志文件，如果没有则返回 null
     */
    @Nullable
    public File getPendingJavaCrashLogFile() {
        var fileName = getPendingJavaCrashLogFileName();
        if (fileName == null) {
            return null;
        }

        var logFile = new File(crashLogDir, fileName);
        if (logFile.exists() && logFile.isFile()) {
            return logFile;
        }

        // 文件不存在，清除标记
        clearPendingJavaCrashFlag();
        return null;
    }

    /**
     * 清除待处理Java崩溃标记
     */
    public void clearPendingJavaCrashFlag() {
        var flagFile = new File(crashLogDir, PENDING_JAVA_CRASH_FLAG);
        if (flagFile.exists() && flagFile.delete()) {
            WeLogger.d("CrashLogManager", "Pending Java crash flag cleared");
        }
    }

    /**
     * 检查是否有待处理的Java崩溃
     *
     * @return 是否有待处理的Java崩溃
     */
    public boolean hasPendingJavaCrash() {
        return getPendingJavaCrashLogFile() != null;
    }

    // ==================== Native 崩溃专用方法 ====================

    /**
     * 获取待处理的Native崩溃日志文件名
     * Native崩溃使用 pending_crash.flag
     *
     * @return 崩溃日志文件名，如果没有则返回null
     */
    @Nullable
    public String getPendingNativeCrashLogFileName() {
        return getPendingCrashLogFileName();
    }

    /**
     * 获取待处理的Native崩溃日志文件
     *
     * @return 崩溃日志文件，如果没有则返回 null
     */
    @Nullable
    public File getPendingNativeCrashLogFile() {
        return getPendingCrashLogFile();
    }

    /**
     * 清除待处理Native崩溃标记
     */
    public void clearPendingNativeCrashFlag() {
        clearPendingCrashFlag();
    }

    /**
     * 检查是否有待处理的Native崩溃
     *
     * @return 是否有待处理的Native崩溃
     */
    public boolean hasPendingNativeCrash() {
        return hasPendingCrash();
    }
}
