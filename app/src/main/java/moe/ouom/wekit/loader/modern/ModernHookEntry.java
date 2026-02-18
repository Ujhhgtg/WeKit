package moe.ouom.wekit.loader.modern;

import static moe.ouom.wekit.host.HostInfo.PACKAGE_NAME_WECHAT;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import moe.ouom.wekit.loader.ModuleLoader;
import moe.ouom.wekit.loader.startup.StartupInfo;
import moe.ouom.wekit.utils.log.WeLogger;

/**
 * Entry point for started Xposed API 100.
 * (Develop Xposed Modules Using Modern Xposed API)
 */
public class ModernHookEntry extends XposedModule {
    private static final String TAG = "ModernHookEntry";

    public ModernHookEntry(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        var packageName = param.getPackageName();
        var processName = param.getApplicationInfo().processName;
        if (packageName.equals(PACKAGE_NAME_WECHAT)) {
            if (param.isFirstPackage()) {
                var modulePath = this.getApplicationInfo().sourceDir;
                StartupInfo.setModulePath(modulePath);
                handleLoadPackage(param.getClassLoader(), param.getApplicationInfo(), modulePath, processName);
            }
        }
    }


    public void handleLoadPackage(@NonNull ClassLoader cl, @NonNull ApplicationInfo ai, @NonNull String modulePath, String processName) {
        var dataDir = ai.dataDir;
        WeLogger.d(TAG, "handleLoadHostPackage: dataDir=" + dataDir + ", modulePath=" + modulePath + ", processName=" + processName);
        try {
            ModuleLoader.initialize(dataDir, cl, Lsp100HookImpl.INSTANCE, Lsp100HookImpl.INSTANCE, modulePath);
        } catch (ReflectiveOperationException e) {
            WeLogger.e(TAG, "failed to invoke ModuleLoader.initialize");
            throw new RuntimeException(e);
        }
    }
}