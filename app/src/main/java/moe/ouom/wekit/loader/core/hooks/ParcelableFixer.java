package moe.ouom.wekit.loader.core.hooks;

import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import moe.ouom.wekit.utils.log.WeLogger;

/**
 * Parcelable 反序列化修复
 */
public class ParcelableFixer {

    private static ClassLoader sHybridClassLoader;
    private static boolean sIsInit = false;

    public static void init(ClassLoader hostClassLoader, ClassLoader moduleClassLoader) {
        if (sIsInit) return;
        sIsInit = true;

        WeLogger.i("ParcelableFixer", "Initializing HybridClassLoader and installing hooks...");

        // 创建混合 ClassLoader：优先宿主，找不到则找模块
        sHybridClassLoader = new ClassLoader(hostClassLoader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return moduleClassLoader.loadClass(name);
            }
        };

        hookIntentMethods();
    }

    public static ClassLoader getHybridClassLoader() {
        return sHybridClassLoader;
    }

    /**
     * 为 Intent 设置正确的 ClassLoader
     */
    private static void fixIntentExtrasClassLoader(Intent intent) {
        if (intent == null || sHybridClassLoader == null) return;
        try {
            // 这步操作只是设置标志位，不会触发 unparcel
            intent.setExtrasClassLoader(sHybridClassLoader);
        } catch (Throwable ignored) {
        }
    }

    private static void hookIntentMethods() {
        final var fixClassLoaderHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // 在任何读取 extras 的操作之前，强制修正 ClassLoader
                if (param.thisObject instanceof Intent) {
                    fixIntentExtrasClassLoader((Intent) param.thisObject);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // 如果返回值是 Bundle，也顺手修一下 Bundle 的 ClassLoader
                var result = param.getResult();
                if (result instanceof Bundle && sHybridClassLoader != null) {
                    ((Bundle) result).setClassLoader(sHybridClassLoader);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Intent.class, "getExtras", fixClassLoaderHook);
            XposedHelpers.findAndHookMethod(Intent.class, "getBundleExtra", String.class, fixClassLoaderHook);

            XposedHelpers.findAndHookMethod(Intent.class, "getParcelableExtra", String.class, fixClassLoaderHook);
            XposedHelpers.findAndHookMethod(Intent.class, "getParcelableArrayListExtra", String.class, fixClassLoaderHook);
            XposedHelpers.findAndHookMethod(Intent.class, "getSerializableExtra", String.class, fixClassLoaderHook);

            // Android 13 及以上的新版强类型方法
            XposedHelpers.findAndHookMethod(Intent.class, "getParcelableExtra", String.class, Class.class, fixClassLoaderHook);
            XposedHelpers.findAndHookMethod(Intent.class, "getParcelableArrayListExtra", String.class, Class.class, fixClassLoaderHook);
            XposedHelpers.findAndHookMethod(Intent.class, "getSerializableExtra", String.class, Class.class, fixClassLoaderHook);
        } catch (Throwable e) {
            WeLogger.w("ParcelableFixer", "Failed to hook some Intent methods: " + e.getMessage());
        }
    }
}