package moe.ouom.wekit.loader.startup;

import android.content.Context;
import java.lang.reflect.Field;
import moe.ouom.wekit.util.log.WeLogger;

public class HybridClassLoader extends ClassLoader {

    private static final ClassLoader sBootClassLoader = Context.class.getClassLoader();
    public static final HybridClassLoader INSTANCE = new HybridClassLoader();

    private HybridClassLoader() {
        super(sBootClassLoader);
    }

    private static ClassLoader sLoaderParentClassLoader;
    // volatile 保证多线程可见性
    private static volatile ClassLoader sHostClassLoader;

    public static void setLoaderParentClassLoader(ClassLoader loaderClassLoader) {
        if (loaderClassLoader == HybridClassLoader.class.getClassLoader()) {
            sLoaderParentClassLoader = null;
        } else {
            sLoaderParentClassLoader = loaderClassLoader;
        }
    }

    public static void setHostClassLoader(ClassLoader hostClassLoader) {
        sHostClassLoader = hostClassLoader;
    }

    /**
     * 如果当前静态变量为空，
     * 则尝试去父级 ClassLoader 里找那个存了值的 HybridClassLoader 类
     */
    public static ClassLoader getHostClassLoader() {
        if (sHostClassLoader != null) {
            return sHostClassLoader;
        }

        synchronized (HybridClassLoader.class) {
            // 双重检查
            if (sHostClassLoader != null) return sHostClassLoader;

            try {
                WeLogger.i("HybridClassLoader: Local sHostClassLoader is null, trying reflection lookup...");

                ClassLoader myLoader = HybridClassLoader.class.getClassLoader();
                assert myLoader != null;
                ClassLoader parentLoader = myLoader.getParent();

                if (parentLoader != null) {
                    Class<?> originalClass = parentLoader.loadClass(HybridClassLoader.class.getName());

                    if (originalClass != null && originalClass != HybridClassLoader.class) {
                        Field targetField = originalClass.getDeclaredField("sHostClassLoader");
                        targetField.setAccessible(true);
                        Object remoteValue = targetField.get(null);

                        if (remoteValue instanceof ClassLoader) {
                            sHostClassLoader = (ClassLoader) remoteValue;
                            WeLogger.i("HybridClassLoader: Successfully stole HostClassLoader from outer world!");
                        } else {
                            WeLogger.e("HybridClassLoader: Reflection found null or invalid object.");
                        }
                    }
                }
            } catch (Throwable e) {
                WeLogger.e("HybridClassLoader: Failed to bridge ClassLoader", e);
            }
        }

        return sHostClassLoader;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return sBootClassLoader.loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        if (sLoaderParentClassLoader != null && name.startsWith("moe.ouom.wekit.loader.")) {
            return sLoaderParentClassLoader.loadClass(name);
        }
        if (isConflictingClass(name)) {
            throw new ClassNotFoundException(name);
        }
        if (sLoaderParentClassLoader != null) {
            try {
                return sLoaderParentClassLoader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        // 关键点：这里使用了 getHostClassLoader() 而不是直接访问 sHostClassLoader
        ClassLoader host = getHostClassLoader();
        if (host != null && isHostClass(name)) {
            try {
                return host.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }

    public static boolean isHostClass(String name) {
        return name.startsWith("com.tencent.")
                || name.startsWith("com.qq.")
                || name.startsWith("oicq.")
                || name.startsWith("tencent.")
                || name.startsWith("cooperation.")
                || name.startsWith("com.tme.")
                || name.startsWith("dov.");
    }

    public static boolean isConflictingClass(String name) {
        return name.startsWith("androidx.") || name.startsWith("android.support.")
                || name.startsWith("kotlin.") || name.startsWith("kotlinx.")
                || name.startsWith("com.tencent.mmkv.")
                || name.startsWith("com.android.tools.r8.")
                || name.startsWith("com.google.android.")
                || name.startsWith("com.google.gson.")
                || name.startsWith("com.google.common.")
                || name.startsWith("com.google.protobuf.")
                || name.startsWith("com.microsoft.appcenter.")
                || name.startsWith("org.intellij.lang.annotations.")
                || name.startsWith("org.jetbrains.annotations.")
                || name.startsWith("com.bumptech.glide.")
                || name.startsWith("com.google.errorprone.annotations.")
                || name.startsWith("org.jf.dexlib2.")
                || name.startsWith("org.jf.util.")
                || name.startsWith("javax.annotation.")
                || name.startsWith("_COROUTINE.");
    }
}