package moe.ouom.wekit.loader.core;

import static moe.ouom.wekit.constants.Constants.CLAZZ_WECHAT_LAUNCHER_UI;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.ouom.wekit.config.RuntimeConfig;
import moe.ouom.wekit.constants.PackageConstants;
import moe.ouom.wekit.dexkit.cache.DexCacheManager;
import moe.ouom.wekit.loader.core.hooks.ActivityProxyHooks;
import moe.ouom.wekit.loader.core.hooks.ParcelableFixer;
import moe.ouom.wekit.utils.common.ModuleRes;
import moe.ouom.wekit.utils.common.SyncUtils;
import moe.ouom.wekit.utils.log.WeLogger;

public class WeLauncher {
    private static final String TAG = "WeLauncher";

    public void init(@NonNull ClassLoader cl, @NonNull ApplicationInfo ai, @NonNull String modulePath, Context context) {
        RuntimeConfig.setHostClassLoader(cl);
        RuntimeConfig.setHostApplicationInfo(ai);

        var currentProcessType = SyncUtils.getProcessType();
        var currentProcessName = SyncUtils.getProcessName();
        WeLogger.i(TAG, "Init start. Process: " + currentProcessName + " (Type: " + currentProcessType + ")");

        try {
            ParcelableFixer.init(
                cl, WeLauncher.class.getClassLoader()
            );
            WeLogger.i(TAG, "ParcelableFixer installed.");
        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to install ParcelableFixer", e);
        }

        // 加载宿主版本信息
        try {
            var pm = context.getPackageManager();
            var pInfo = pm.getPackageInfo(context.getPackageName(), 0);

            if (pInfo != null) {
                RuntimeConfig.setWechatVersionName(pInfo.versionName);
                RuntimeConfig.setWechatVersionCode(pInfo.getLongVersionCode());
                DexCacheManager.INSTANCE.init(context, Objects.requireNonNull(pInfo.versionName));
            }
        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to load version info", e);
        }

        // 仅在主进程安装 Activity 代理 Hook
        if (currentProcessType == SyncUtils.PROC_MAIN) {
            try {
                var appContext = context.getApplicationContext();
                if (appContext == null) appContext = context;

                ActivityProxyHooks.initForStubActivity(appContext);
                WeLogger.i(TAG, "Activity Proxy Hooks installed successfully (Main Process).");
            } catch (Throwable e) {
                WeLogger.e(TAG, "Failed to install Activity Proxy Hooks", e);
            }

            initMainProcessHooks(cl);
        } else {
            WeLogger.i(TAG, "Skipping UI hooks for non-main process: " + currentProcessName);
        }

        // 加载功能模块
        try {
            HooksLoader.load(currentProcessType);
        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to load modules via HooksLoader", e);
        }
    }

    /**
     * 仅在主进程执行的 Hook 逻辑
     */
    private void initMainProcessHooks(ClassLoader cl) {
        WeLogger.i(TAG, "Initializing Main Process Hooks...");

        // Hook LauncherUI.onResume
        try {
            var launcherUIClass = XposedHelpers.findClass(CLAZZ_WECHAT_LAUNCHER_UI, cl);

            XposedHelpers.findAndHookMethod(launcherUIClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    var activity = (Activity) param.thisObject;
                    ModuleRes.init(activity, PackageConstants.PACKAGE_NAME_SELF);
                }
            });

        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to hook LauncherUI.onResume", e);
        }

        // Hook LauncherUI.onCreate
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass(CLAZZ_WECHAT_LAUNCHER_UI, cl), "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    var activity = (Activity) param.thisObject;
                    RuntimeConfig.setLauncherUIActivity(activity);
                    var sharedPreferences = activity.getSharedPreferences("com.tencent.mm_preferences", 0);

                    RuntimeConfig.setLogin_weixin_username(sharedPreferences.getString("login_weixin_username", ""));
                    RuntimeConfig.setLast_login_nick_name(sharedPreferences.getString("last_login_nick_name", ""));
                    RuntimeConfig.setLogin_user_name(sharedPreferences.getString("login_user_name", ""));
                    RuntimeConfig.setLast_login_uin(sharedPreferences.getString("last_login_uin", "0"));
                }
            });
        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to hook LauncherUI.onCreate", e);
        }
    }
}