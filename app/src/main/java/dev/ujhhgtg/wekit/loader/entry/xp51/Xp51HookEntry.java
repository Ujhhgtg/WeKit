package dev.ujhhgtg.wekit.loader.entry.xp51;

import androidx.annotation.Keep;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.ujhhgtg.wekit.constants.PackageNames;
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader;

@Keep
public class Xp51HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static String sCurrentPackageName = null;
    private static XC_LoadPackage.LoadPackageParam sLoadPackageParam = null;
    private static StartupParam sInitZygoteStartupParam = null;
    private static String sModulePath = null;

    public static XC_LoadPackage.LoadPackageParam getLoadPackageParam() {
        if (sLoadPackageParam == null) {
            throw new IllegalStateException("LoadPackageParam is null");
        }
        return sLoadPackageParam;
    }

    public static String getModulePath() {
        if (sModulePath == null) {
            throw new IllegalStateException("Module path is null");
        }
        return sModulePath;
    }

    public static StartupParam getInitZygoteStartupParam() {
        if (sInitZygoteStartupParam == null) {
            throw new IllegalStateException("InitZygoteStartupParam is null");
        }
        return sInitZygoteStartupParam;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws ReflectiveOperationException {
        sLoadPackageParam = lpparam;
        if (lpparam.packageName.equals(PackageNames.THIS)) {
            Xp51HookStatusInit.init(lpparam.classLoader);
        } else if (PackageNames.isWeChat(lpparam.packageName)) {
            if (sInitZygoteStartupParam == null) {
                throw new IllegalStateException("handleLoadPackage: sInitZygoteStartupParam is null");
            }
            sCurrentPackageName = lpparam.packageName;
            ModuleLoader.init(
                lpparam.appInfo.dataDir,
                lpparam.classLoader,
                Xp51HookImpl.INSTANCE,
                Xp51HookImpl.INSTANCE,
                getModulePath(),
                true
            );
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        sInitZygoteStartupParam = startupParam;
        sModulePath = startupParam.modulePath;
    }
}
