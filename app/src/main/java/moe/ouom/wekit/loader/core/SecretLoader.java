package moe.ouom.wekit.loader.core;

import java.lang.reflect.Method;

import moe.ouom.wekit.BuildConfig;
import moe.ouom.wekit.loader.TransitClassLoader;
import moe.ouom.wekit.loader.dyn.MemoryDexLoader;
import moe.ouom.wekit.util.log.WeLogger;

public class SecretLoader {

    private static final String TAG = "SecretLoader";
    private static final String TARGET_CLASS = "moe.ouom.wekit.hooks.core.HookItemLoader";
    private static final String TARGET_METHOD = "loadHookItem";

    /**
     * 动态加载并执行 Hooks
     *
     * @param processType 当前进程类型
     */
    public static void load(int processType) {
        try {
            WeLogger.i(TAG, "Attempting to load hidden hooks...");

            byte[] dexBytes = WeKitNative.getHiddenDex();

            // DEBUG 模式下不进行动态加载，直接走 Fallback
            if (BuildConfig.DEBUG) {
                tryFallbackLoad(processType);
                return;
            }

            if (dexBytes == null || dexBytes.length == 0) {
                WeLogger.e(TAG, "Hidden DEX is empty! (Is this a Debug build?)");
                tryFallbackLoad(processType);
                return;
            }

            // 创建中转 ClassLoader
            // 它会让隐藏 DEX 优先看到模块里的类，而不是微信里的
            TransitClassLoader priorityLoader = new TransitClassLoader();
            // 使用 priorityLoader 作为父加载器
            ClassLoader secretLoader = MemoryDexLoader.createClassLoaderWithDex(dexBytes, priorityLoader);

            Class<?> loaderClass = secretLoader.loadClass(TARGET_CLASS);
            Object instance = loaderClass.newInstance();

            Method method = loaderClass.getDeclaredMethod(TARGET_METHOD, int.class);
            method.setAccessible(true);
            method.invoke(instance, processType);

            // 注册 Bridge
            try {
                // 加载 Hidden DEX 中的 Factory 类
                Class<?> factoryClass = secretLoader.loadClass("moe.ouom.wekit.hooks.core.factory.HookItemFactory");

                // 获取 INSTANCE 静态字段
                java.lang.reflect.Field instanceField = factoryClass.getDeclaredField("INSTANCE");
                Object factoryInstance = instanceField.get(null);

                // 注册给主 DEX 的 Bridge
                // 这里的强转 IHookFactoryDelegate 是安全的，因为接口定义在主 DEX
                moe.ouom.wekit.core.bridge.HookFactoryBridge.INSTANCE.registerDelegate(
                        (moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate) factoryInstance
                );

                WeLogger.i("HookFactoryBridge registered successfully!");

            } catch (Throwable e) {
                WeLogger.e("Failed to register HookFactoryBridge", e);
            }

            WeLogger.i("Hidden hooks loaded successfully!");

        } catch (Throwable e) {
            WeLogger.e("Failed to load hidden hooks", e);
        }
    }

    private static void tryFallbackLoad(int processType) {
        try {
            Class<?> cls = Class.forName(TARGET_CLASS);
            Object instance = cls.newInstance();
            Method method = cls.getDeclaredMethod(TARGET_METHOD, int.class);
            method.setAccessible(true);
            method.invoke(instance, processType);

            try {
                Class<?> factoryClass = Class.forName("moe.ouom.wekit.hooks.core.factory.HookItemFactory");
                java.lang.reflect.Field instanceField = factoryClass.getDeclaredField("INSTANCE");
                Object factoryInstance = instanceField.get(null);

                assert factoryInstance != null;
                moe.ouom.wekit.core.bridge.HookFactoryBridge.INSTANCE.registerDelegate(
                        (moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate) factoryInstance
                );

                WeLogger.i("[Fallback] HookFactoryBridge registered successfully!");
            } catch (Throwable e) {
                WeLogger.e("[Fallback] Failed to register HookFactoryBridge", e);
            }

            WeLogger.i("Fallback load success");
        } catch (Exception e) {
            WeLogger.e("Fallback load failed", e);
        }
    }
}