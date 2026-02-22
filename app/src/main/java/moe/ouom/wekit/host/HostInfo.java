package moe.ouom.wekit.host;

import android.app.Application;

import androidx.annotation.NonNull;

import moe.ouom.wekit.BuildConfig;

/**
 * Helper class for getting host information (WeChat version). Keep it as simple as possible.
 */
public class HostInfo {

    public static final String PACKAGE_NAME_WECHAT = "com.tencent.mm";
    public static final String PACKAGE_NAME_SELF = BuildConfig.APPLICATION_ID;

    private HostInfo() {
        throw new AssertionError("No instance for you!");
    }

    @NonNull
    public static Application getApplication() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getApplication();
    }

    @NonNull
    public static String getPackageName() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getPackageName();
    }

    @NonNull
    public static String getAppName() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getHostName();
    }

    @NonNull
    public static String getVersionName() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getVersionName();
    }

    public static int getVersionCode32() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getVersionCode32();
    }

    public static int getVersionCode() {
        return getVersionCode32();
    }

    public static long getLongVersionCode() {
        return moe.ouom.wekit.host.impl.HostInfo.getHostInfo().getVersionCode();
    }

    public static boolean isInModuleProcess() {
        return moe.ouom.wekit.host.impl.HostInfo.isInModuleProcess();
    }

    public static boolean isInHostProcess() {
        return !moe.ouom.wekit.host.impl.HostInfo.isInModuleProcess();
    }

    public static boolean isAndroidxFileProviderAvailable() {
        return moe.ouom.wekit.host.impl.HostInfo.isAndroidxFileProviderAvailable();
    }

    public static boolean isWeChat() {
        return moe.ouom.wekit.host.impl.HostInfo.isWeChat();
    }

    public static boolean requireMinWeChatVersion(int versionCode) {
        return isWeChat() && getLongVersionCode() >= versionCode;
    }
}