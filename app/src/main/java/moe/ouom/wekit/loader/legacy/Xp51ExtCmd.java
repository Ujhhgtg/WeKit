package moe.ouom.wekit.loader.legacy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.robv.android.xposed.XposedBridge;
import moe.ouom.wekit.loader.ModuleLoader;
import moe.ouom.wekit.utils.common.CheckUtils;

public class Xp51ExtCmd {

    private Xp51ExtCmd() {
    }

    public static Object handleQueryExtension(@NonNull String cmd, @Nullable Object[] arg) {
        CheckUtils.checkNonNull(cmd, "cmd");
        return switch (cmd) {
            case "GetXposedBridgeClass" -> XposedBridge.class;
            case "GetLoadPackageParam" -> LegacyHookEntry.getLoadPackageParam();
            case "GetInitZygoteStartupParam" -> LegacyHookEntry.getInitZygoteStartupParam();
            case "GetInitErrors" -> ModuleLoader.getInitErrors();
            default -> null;
        };
    }

}
