package moe.ouom.wekit.loader.core;

import static moe.ouom.wekit.constants.Constants.CLAZZ_WECHAT_LAUNCHER_UI;
import static moe.ouom.wekit.util.common.SyncUtils.postDelayed;

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
import moe.ouom.wekit.dexkit.TargetManager;
import moe.ouom.wekit.security.SignatureVerifier;
import moe.ouom.wekit.util.Initiator;
import moe.ouom.wekit.util.common.ModuleRes;
import moe.ouom.wekit.util.common.SyncUtils;
import moe.ouom.wekit.util.log.WeLogger;

public class WeLauncher {
    public void init(@NonNull ClassLoader cl, @NonNull ApplicationInfo ai, @NonNull String modulePath, Context context) {
        // 保存到 RuntimeConfig
        RuntimeConfig.setHostClassLoader(cl);
        RuntimeConfig.setHostApplicationInfo(ai);
        Initiator.init(context.getClassLoader());

        try {
            PackageManager pm = context.getPackageManager();
            // 获取宿主包名的详细信息
            PackageInfo pInfo = pm.getPackageInfo(context.getPackageName(), 0);

            if (pInfo != null) {
                // 存入 CacheConfig
                RuntimeConfig.setWechatVersionName(pInfo.versionName);
                RuntimeConfig.setWechatVersionCode(pInfo.getLongVersionCode());

                // 初始化 DexCacheManager
                assert pInfo.versionName != null;
                moe.ouom.wekit.dexkit.cache.DexCacheManager.INSTANCE.init(context, pInfo.versionName);

                if (!Objects.equals(pInfo.versionName, TargetManager.INSTANCE.getLastWeChatVersion())){
                    TargetManager.INSTANCE.setNeedFindTarget(true);
                }

                if (TargetManager.INSTANCE.getTargetManagerVersion() != TargetManager.VERSION) {
                    TargetManager.INSTANCE.setNeedFindTarget(true);
                }


                assert pInfo.versionName != null;
                TargetManager.INSTANCE.setLastWeChatVersion(pInfo.versionName);
                TargetManager.INSTANCE.setTargetManagerVersion(TargetManager.VERSION);

                WeLogger.i("WeChat Version cached: " + RuntimeConfig.getWechatVersionName() + " (" + RuntimeConfig.getWechatVersionCode() + ")");
            }
        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to load version info: " + e);
        }


        /////  com.tencent.mm.ui.LauncherUI   /////

        // onResume
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
            WeLogger.e("WeLauncher: Failed to hook LauncherUI: " + e);
        }

        // onCreate
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass(CLAZZ_WECHAT_LAUNCHER_UI, cl), "onCreate", Bundle.class, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    SharedPreferences sharedPreferences = activity.getSharedPreferences("com.tencent.mm_preferences", 0);
                    String login_weixin_username = sharedPreferences.getString("login_weixin_username", "null");
                    String last_login_nick_name = sharedPreferences.getString("last_login_nick_name", "null");
                    String login_user_name = sharedPreferences.getString("login_user_name", "null");
                    String last_login_uin = sharedPreferences.getString("last_login_uin", "null");

                    // login_weixin_username: wxid_apfe8lfoeoad13
                    // last_login_nick_name: 帽子叔叔
                    // login_user_name: 150665766147
                    // last_login_uin: 1293948946
                    RuntimeConfig.setLogin_weixin_username(login_weixin_username);
                    RuntimeConfig.setLast_login_nick_name(last_login_nick_name);
                    RuntimeConfig.setLogin_user_name(login_user_name);
                    RuntimeConfig.setLast_login_uin(last_login_uin);
                    RuntimeConfig.setLauncherUIActivity(activity);
//                    Logger.d("login_weixin_username: " + login_weixin_username + "\nlast_login_nick_name: " + last_login_nick_name + "\nlogin_user_name: " + login_user_name + "\nlast_login_uin: " + last_login_uin);
                }
            });

        } catch (Throwable e) {
            WeLogger.e("WeLauncher: Failed to hook LauncherUI: " + e);
        }

        /////  -----------------------   /////
        if (!SignatureVerifier.isSignatureValid()) {
            WeLogger.e("WeLauncher", "签名校验失败，跳过 Hook 加载");
            return;
        }

//        HookItemLoader hookItemLoader = new HookItemLoader();
//        hookItemLoader.loadHookItem(SyncUtils.getProcessType());

        SecretLoader.load(SyncUtils.getProcessType());
    }
}