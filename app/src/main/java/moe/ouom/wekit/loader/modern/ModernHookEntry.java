package moe.ouom.wekit.loader.modern;

import static moe.ouom.wekit.host.HostInfo.PACKAGE_NAME_WECHAT;
import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import moe.ouom.wekit.loader.ModuleLoader;
import moe.ouom.wekit.loader.startup.StartupInfo;
import moe.ouom.wekit.util.log.WeLogger;

/**
 * Entry point for started Xposed API 100.
 * (Develop Xposed Modules Using Modern Xposed API)
 * */


public class ModernHookEntry extends XposedModule {
    private static ModuleLoadedParam mModule;
    private static PackageLoadedParam mModuleLoadedParam;

    public ModernHookEntry(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
        mModule = param;
    }


    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String packageName = param.getPackageName();
        String processName = param.getApplicationInfo().processName;
        if (packageName.equals(PACKAGE_NAME_WECHAT)) {
            if (param.isFirstPackage()) {
                String modulePath = this.getApplicationInfo().sourceDir;
                StartupInfo.setModulePath(modulePath);
                mModuleLoadedParam = param;
                try {
                    handleLoadPackage(param.getClassLoader(), param.getApplicationInfo(), modulePath, processName);
                } catch (ClassNotFoundException e) {
                    WeLogger.e(e);
                }
            }
        }
    }



    public void handleLoadPackage(@NonNull ClassLoader cl, @NonNull ApplicationInfo ai, @NonNull String modulePath, String processName) throws ClassNotFoundException {
        String dataDir = ai.dataDir;
        WeLogger.d("ModernHookEntry.handleLoadHostPackage: dataDir=" + dataDir + ", modulePath=" + modulePath + ", processName=" + processName);
        try {
            ModuleLoader.initialize(dataDir, cl, Lsp100HookImpl.INSTANCE, Lsp100HookImpl.INSTANCE, modulePath, true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }



    }

}