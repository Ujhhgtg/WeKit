package moe.ouom.wekit.loader.core;

import static moe.ouom.wekit.constants.Constants.CLAZZ_WECHAT_LAUNCHER_UI;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.ouom.wekit.config.RuntimeConfig;
import moe.ouom.wekit.constants.PackageConstants;
import moe.ouom.wekit.security.SignatureVerifier;
import moe.ouom.wekit.util.Initiator;
import moe.ouom.wekit.util.common.ModuleRes;
import moe.ouom.wekit.util.common.SyncUtils;
import moe.ouom.wekit.util.log.WeLogger;

public class WeLauncher {

    public void init(@NonNull ClassLoader cl, @NonNull ApplicationInfo ai, @NonNull String modulePath, Context context) {
        RuntimeConfig.setHostClassLoader(cl);
        RuntimeConfig.setHostApplicationInfo(ai);
        Initiator.init(context.getClassLoader());

        int currentProcessType = SyncUtils.getProcessType();
        String currentProcessName = SyncUtils.getProcessName();

        WeLogger.i("WeLauncher", "Init start. Process: " + currentProcessName + " (Type: " + currentProcessType + ")");

        // 加载宿主版本信息
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pInfo = pm.getPackageInfo(context.getPackageName(), 0);

            if (pInfo != null) {
                RuntimeConfig.setWechatVersionName(pInfo.versionName);
                RuntimeConfig.setWechatVersionCode(pInfo.getLongVersionCode());

                moe.ouom.wekit.dexkit.cache.DexCacheManager.INSTANCE.init(context, Objects.requireNonNull(pInfo.versionName));

                WeLogger.i("Host Version: " + RuntimeConfig.getWechatVersionName() + " (" + RuntimeConfig.getWechatVersionCode() + ")");
            }
        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to load version info", e);
        }

        // 签名校验
        if (!SignatureVerifier.isSignatureValid()) {
            WeLogger.e("WeLauncher", "Signature verification failed. Aborting.");
            return;
        }

        if (currentProcessType == SyncUtils.PROC_MAIN) {
            initMainProcessHooks(cl);
        } else {
            WeLogger.i("WeLauncher", "Skipping UI hooks for non-main process: " + currentProcessName);
        }

        // 加载功能模块
        // 将当前进程类型传递进去，由 SecretLoader 内部根据 @HookItem 注解进行筛选
        try {
            SecretLoader.load(currentProcessType);
        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to load modules via SecretLoader", e);
        }
    }

    /**
     * 仅在主进程执行的 Hook 逻辑
     */
    private void initMainProcessHooks(ClassLoader cl) {
        WeLogger.i("WeLauncher", "Initializing Main Process Hooks...");

        // Hook LauncherUI.onResume
        try {
            Class<?> launcherUIClass = XposedHelpers.findClass(CLAZZ_WECHAT_LAUNCHER_UI, cl);

            XposedHelpers.findAndHookMethod(launcherUIClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    ModuleRes.init(activity, PackageConstants.PACKAGE_NAME_SELF);
                }
            });

        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to hook LauncherUI.onResume", e);
        }

        // Hook LauncherUI.onCreate
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass(CLAZZ_WECHAT_LAUNCHER_UI, cl), "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    RuntimeConfig.setLauncherUIActivity(activity);
                    SharedPreferences sharedPreferences = activity.getSharedPreferences("com.tencent.mm_preferences", 0);

                    String login_weixin_username = sharedPreferences.getString("login_weixin_username", "");
                    String last_login_nick_name = sharedPreferences.getString("last_login_nick_name", "");
                    String login_user_name = sharedPreferences.getString("login_user_name", "");
                    String last_login_uin = sharedPreferences.getString("last_login_uin", "0");

                    RuntimeConfig.setLogin_weixin_username(login_weixin_username);
                    RuntimeConfig.setLast_login_nick_name(last_login_nick_name);
                    RuntimeConfig.setLogin_user_name(login_user_name);
                    RuntimeConfig.setLast_login_uin(last_login_uin);

                    WeLogger.d("WeLauncher", "User Info Loaded: " + last_login_nick_name + " (" + login_weixin_username + ")");
                }
            });

        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to hook LauncherUI.onCreate", e);
        }
    }
}