package moe.ouom.wekit.loader;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.BaseDexClassLoader;
import moe.ouom.wekit.loader.hookapi.IHookBridge;
import moe.ouom.wekit.loader.hookapi.ILoaderService;
import moe.ouom.wekit.util.log.WeLogger;

public class ModuleLoader {

    private ModuleLoader() {}

    private static boolean sLoaded = false;
    private static final ArrayList<Throwable> sInitErrors = new ArrayList<>(1);

    @Nullable
    public static String findTargetModulePath(@NonNull String hostDataDir) {
        // TODO: 2024-07-21 implement this method
        return null;
    }

    @SuppressLint("ObsoleteSdkInt")
    public static ClassLoader createTargetClassLoader(@NonNull File path, @NonNull String dataDirPath) {
        ClassLoader parent = new TransitClassLoader();
        File dataDir = new File(dataDirPath);
        if (!dataDir.canWrite()) {
            sInitErrors.add(new IOException("createTargetClassLoader: dataDir is not writable: " + dataDirPath));
            return null;
        }
        // create odex directory if sdk < 26
        File odexDir;
        if (android.os.Build.VERSION.SDK_INT < 26) {
            odexDir = new File(dataDir, "app_odex");
            if (!odexDir.exists() && !odexDir.mkdirs()) {
                sInitErrors.add(new IOException("createTargetClassLoader: failed to create odexDir: " + odexDir));
                return null;
            }
        } else {
            // optimizedDirectory – this parameter is deprecated and has no effect since API level 26.
            odexDir = null;
        }
        // create new class loader
        ClassLoader cl = new BaseDexClassLoader(path.getAbsolutePath(), odexDir, null, parent);
        return cl;
    }

    public static void initialize(
            @NonNull String hostDataDir,
            @NonNull ClassLoader hostClassLoader,
            @NonNull ILoaderService loaderService,
            @Nullable IHookBridge hookBridge,
            @NonNull String selfPath,
            boolean allowDynamicLoad
    ) throws ReflectiveOperationException {
        if (sLoaded) {
            return;
        }
        File targetModule = null;
        boolean useDynamicLoad = false;
        if (allowDynamicLoad) {
            try {
                String path = findTargetModulePath(hostDataDir);
                if (path != null) {
                    targetModule = new File(path);
                }
            } catch (Exception | Error e) {
                sInitErrors.add(e);
                WeLogger.e("initialize: findTargetModulePath failed", e);
            }
        }
        if (targetModule != null && targetModule.isFile() && !targetModule.canWrite()) {
            // ART requires W^X since Android 14
            useDynamicLoad = true;
        }
        ClassLoader targetClassLoader = null;
        try {
            if (useDynamicLoad) {
                targetClassLoader = createTargetClassLoader(targetModule, hostDataDir);
            }
        } catch (Exception | Error e) {
            sInitErrors.add(e);
            WeLogger.e("initialize: createTargetClassLoader failed", e);
        }
        // if we failed to create targetClassLoader, fallback to normal startup
        String modulePath;
        if (targetClassLoader == null) {
            targetClassLoader = ModuleLoader.class.getClassLoader();
            modulePath = selfPath;
        } else {
            modulePath = targetModule.getAbsolutePath();
        }
        assert targetClassLoader != null;
        // invoke the startup routine
        Class<?> kUnifiedEntryPoint = targetClassLoader.loadClass("moe.ouom.wekit.loader.startup.UnifiedEntryPoint");
        Method initialize = kUnifiedEntryPoint.getMethod("entry",
                String.class, String.class, ILoaderService.class, ClassLoader.class, IHookBridge.class);
        sLoaded = true;
        initialize.invoke(null, modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge);
    }

    public static List<Throwable> getInitErrors() {
        return sInitErrors;
    }
}
