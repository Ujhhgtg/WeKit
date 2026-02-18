package moe.ouom.wekit.loader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import moe.ouom.wekit.loader.hookapi.IHookBridge;
import moe.ouom.wekit.loader.hookapi.ILoaderService;
import moe.ouom.wekit.loader.startup.UnifiedEntryPoint;

public class ModuleLoader {

    private static boolean sLoaded = false;
    private static final ArrayList<Throwable> sInitErrors = new ArrayList<>(1);

    public static void initialize(
        @NonNull String hostDataDir,
        @NonNull ClassLoader hostClassLoader,
        @NonNull ILoaderService loaderService,
        @Nullable IHookBridge hookBridge,
        @NonNull String modulePath
    ) throws ReflectiveOperationException {
        if (sLoaded) {
            return;
        }
        // invoke the startup routine
        sLoaded = true;
        UnifiedEntryPoint.INSTANCE.entry(modulePath, hostDataDir, loaderService, hostClassLoader, hookBridge);
    }

    public static List<Throwable> getInitErrors() {
        return sInitErrors;
    }
}
