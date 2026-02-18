package moe.ouom.wekit.loader.core;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import moe.ouom.wekit.utils.log.WeLogger;

@Keep
public class WeKitNative {
    private static final String TAG = "WeKitNative";
    private static volatile boolean sLibraryLoaded = false;

    public static void setLibraryLoaded() {
        sLibraryLoaded = true;
        WeLogger.i(TAG, "native library marked as loaded");
    }

    /**
     * 初始化入口
     */
    public static void init(@NonNull String flag) {
        if (!sLibraryLoaded) {
            WeLogger.e(TAG, "Native library not loaded, verification failed");
        }
    }

    /**
     * 检查Native库是否已加载
     */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }
}