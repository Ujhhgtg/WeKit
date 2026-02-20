package moe.ouom.wekit.loader

import moe.ouom.wekit.loader.startup.StartupInfo

object LoaderExtensionHelper {
    const val CMD_GET_XPOSED_BRIDGE_CLASS: String = "GetXposedBridgeClass"

    fun getXposedBridgeClass(): Class<*>? {
        val loaderService = StartupInfo.getLoaderService()
        return loaderService.queryExtension(CMD_GET_XPOSED_BRIDGE_CLASS) as Class<*>?
    }
}
