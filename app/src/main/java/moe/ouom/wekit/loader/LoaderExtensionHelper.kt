package moe.ouom.wekit.loader;

import androidx.annotation.Nullable;

import moe.ouom.wekit.loader.startup.StartupInfo;


public class LoaderExtensionHelper {

    public static final String CMD_GET_XPOSED_BRIDGE_CLASS = "GetXposedBridgeClass";

    private LoaderExtensionHelper() {
    }

    @Nullable
    public static Class<?> getXposedBridgeClass() {
        var loaderService = StartupInfo.getLoaderService();
        return (Class<?>) loaderService.queryExtension(CMD_GET_XPOSED_BRIDGE_CLASS);
    }


}
