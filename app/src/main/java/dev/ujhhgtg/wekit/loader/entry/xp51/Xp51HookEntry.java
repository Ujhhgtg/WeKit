package dev.ujhhgtg.wekit.loader.entry.xp51;

import androidx.annotation.Keep;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.ujhhgtg.wekit.constants.PackageNames;
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader;

@Keep
public class Xp51HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static XC_LoadPackage.LoadPackageParam param = null;
    private static StartupParam startupParam = null;
    private static String modulePath = null;

    public static XC_LoadPackage.LoadPackageParam getLoadPackageParam() {
        if (param == null) {
            throw new IllegalStateException("LoadPackageParam is null");
        }
        return param;
    }

    public static String getModulePath() {
        if (modulePath == null) {
            throw new IllegalStateException("Module path is null");
        }
        return modulePath;
    }

    public static StartupParam getInitZygoteStartupParam() {
        if (startupParam == null) {
            throw new IllegalStateException("InitZygoteStartupParam is null");
        }
        return startupParam;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws ReflectiveOperationException {
        param = lpparam;
        if (lpparam.packageName.equals(PackageNames.THIS)) {
            Xp51HookStatusInit.init(lpparam.classLoader);
        } else if (PackageNames.isWeChat(lpparam.packageName)) {
            if (startupParam == null) {
                throw new IllegalStateException("handleLoadPackage: sInitZygoteStartupParam is null");
            }
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
        Xp51HookEntry.startupParam = startupParam;
        modulePath = startupParam.modulePath;
    }
}
