package moe.ouom.wekit.loader.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import moe.ouom.wekit.BuildConfig;
import moe.ouom.wekit.loader.TransitClassLoader;
import moe.ouom.wekit.loader.dyn.MemoryDexLoader;
import moe.ouom.wekit.util.log.WeLogger;

public class SecretLoader {

    private static final String TAG = "SecretLoader";
    private static final String TARGET_CLASS = "moe.ouom.wekit.hooks.core.HookItemLoader";
    private static final String TARGET_METHOD = "loadHookItem";
    private static final String FACTORY_CLASS = "moe.ouom.wekit.hooks.core.factory.HookItemFactory";

    /**
     * 动态加载并执行 Hooks
     *
     * @param processType 当前进程类型
     */
    public static void load(int processType) {
        WeLogger.i(TAG, "Attempting to load hidden hooks...");

        // DEBUG 模式下不进行动态加载，直接走 Fallback
        if (BuildConfig.DEBUG) {
            tryFallbackLoad(processType);
            return;
        }

        byte[] dexBytes = WeKitNative.getHiddenDex();
        if (dexBytes == null || dexBytes.length == 0) {
            WeLogger.e(TAG, "Hidden DEX is empty! (Is this a Debug build?)");
            tryFallbackLoad(processType);
            return;
        }

        ClassLoader secretLoader;
        try {
            // 创建中转 ClassLoader
            // 它会让隐藏 DEX 优先看到模块里的类，而不是微信里的
            TransitClassLoader priorityLoader = new TransitClassLoader();
            // 使用 priorityLoader 作为父加载器
            secretLoader = MemoryDexLoader.createClassLoaderWithDex(dexBytes, priorityLoader);
        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to create MemoryDexLoader", e);
            tryFallbackLoad(processType);
            return;
        }

        // 加载并实例化 HookItemLoader
        try {
            Class<?> loaderClass = secretLoader.loadClass(TARGET_CLASS);

            Constructor<?> constructor = loaderClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();

            Method method = loaderClass.getDeclaredMethod(TARGET_METHOD, int.class);
            method.setAccessible(true);
            method.invoke(instance, processType);

            WeLogger.i(TAG, "HookItemLoader invoked successfully via Hidden DEX.");

        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to instantiate or invoke " + TARGET_CLASS + " from Hidden DEX", e);
            // 这里是否要走 Fallback 取决于策略，通常如果是 DEX 加载成功但类出错，Fallback 也没用，故不强制 Fallback
        }

        // 注册 HookFactoryBridge
        try {
            // 加载 Hidden DEX 中的 Factory 类
            Class<?> factoryClass = secretLoader.loadClass(FACTORY_CLASS);

            // 获取 INSTANCE 静态字段
            Field instanceField = factoryClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object factoryInstance = instanceField.get(null);

            // 注册给主 DEX 的 Bridge
            if (factoryInstance instanceof moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate) {
                moe.ouom.wekit.core.bridge.HookFactoryBridge.INSTANCE.registerDelegate(
                        (moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate) factoryInstance
                );
                WeLogger.i(TAG, "HookFactoryBridge registered successfully!");
            } else {
                WeLogger.e(TAG, "Loaded factory instance does not implement IHookFactoryDelegate!");
            }

        } catch (Throwable e) {
            WeLogger.e(TAG, "Failed to register HookFactoryBridge from Hidden DEX", e);
        }
    }

    private static void tryFallbackLoad(int processType) {
        WeLogger.i(TAG, "Entering fallback load routine...");
        try {
            // Fallback 实例化
            Class<?> cls = Class.forName(TARGET_CLASS);

            // 设置可访问
            Constructor<?> constructor = cls.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();

            Method method = cls.getDeclaredMethod(TARGET_METHOD, int.class);
            method.setAccessible(true);
            method.invoke(instance, processType);

            // Fallback Bridge 注册
            try {
                Class<?> factoryClass = Class.forName(FACTORY_CLASS);
                Field instanceField = factoryClass.getDeclaredField("INSTANCE");
                instanceField.setAccessible(true);
                Object factoryInstance = instanceField.get(null);

                if (factoryInstance != null) {
                    moe.ouom.wekit.core.bridge.HookFactoryBridge.INSTANCE.registerDelegate(
                            (moe.ouom.wekit.core.bridge.api.IHookFactoryDelegate) factoryInstance
                    );
                    WeLogger.i(TAG, "[Fallback] HookFactoryBridge registered successfully!");
                } else {
                    WeLogger.e(TAG, "[Fallback] Factory INSTANCE is null.");
                }
            } catch (Throwable e) {
                WeLogger.e(TAG, "[Fallback] Failed to register HookFactoryBridge", e);
            }

            WeLogger.i(TAG, "Fallback load success");
        } catch (ClassNotFoundException e) {
            // 致命错误
            WeLogger.e(TAG, "[Fallback] Critical: Target class not found. Check ProGuard rules.", e);
        } catch (NoSuchMethodException e) {
            WeLogger.e(TAG, "[Fallback] Critical: Constructor or method not found.", e);
        } catch (Throwable e) {
            WeLogger.e(TAG, "[Fallback] Load failed with unexpected error", e);
        }
    }
}