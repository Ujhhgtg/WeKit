package dev.ujhhgtg.wekit.loader.entry.xp51;

import androidx.annotation.Keep;

import de.robv.android.xposed.XposedBridge;
import dev.ujhhgtg.wekit.constants.PackageNames;

public class Xp51HookStatusInit {

    private Xp51HookStatusInit() {
    }

    @Keep
    public static void init(ClassLoader classLoader) throws ReflectiveOperationException {
        var kHookStatusImpl = classLoader.loadClass(PackageNames.MODULE + ".utils.featurestatus.featurestatusImpl");
        var f = kHookStatusImpl.getDeclaredField("sZygoteHookMode");
        f.setAccessible(true);
        f.set(null, true);
        @SuppressWarnings("ConstantValue")
        var dexObfsEnabled = !"de.robv.android.xposed.XposedBridge".equals(XposedBridge.class.getName());
        String hookProvider = null;
        // noinspection ConstantValue
        if (dexObfsEnabled) {
            f = kHookStatusImpl.getDeclaredField("sIsLsposedDexObfsEnabled");
            f.setAccessible(true);
            f.set(null, true);
            hookProvider = "LSPosed";
        } else {
            String bridgeTag = null;
            try {
                // noinspection JavaReflectionMemberAccess
                bridgeTag = (String) XposedBridge.class.getDeclaredField("TAG").get(null);
            } catch (ReflectiveOperationException ignored) {
            }
            if (bridgeTag != null) {
                if (bridgeTag.startsWith("LSPosed")) {
                    hookProvider = "LSPosed";
                } else if (bridgeTag.startsWith("EdXposed")) {
                    hookProvider = "EdXposed";
                }
            }
        }
        if (hookProvider != null) {
            f = kHookStatusImpl.getDeclaredField("sZygoteHookProvider");
            f.setAccessible(true);
            f.set(null, hookProvider);
        }
    }
}
