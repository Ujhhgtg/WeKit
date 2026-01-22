package moe.ouom.wekit.loader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Keep;

import moe.ouom.wekit.security.SignatureVerifier;
import moe.ouom.wekit.util.log.WeLogger;

@Keep
public class WeKitNative {

    private static final String TAG = "WeKitNative";
    private static volatile boolean sLibraryLoaded = false;

    public static native byte[] getHiddenDex();

    private static native boolean doInit(String signatureHash);


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
            return;
        }
        if (!SignatureVerifier.isSignatureValid()) {
            WeLogger.e(TAG, "Java layer signature check failed");
            return;
        }

        try {
            // 检查 doInit 的布尔返回值
            boolean success = doInit(flag);
            if (!success) {
                WeLogger.e(TAG, "Native init failed (Check Logs for details)");
            } else {
                WeLogger.i(TAG, "Native init successful");
            }
        } catch (UnsatisfiedLinkError | Exception e) {
            WeLogger.e("Native init exception", e);
        }
    }

    /**
     * 检查Native库是否已加载
     */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }
}