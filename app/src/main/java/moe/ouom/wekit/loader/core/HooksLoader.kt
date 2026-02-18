package moe.ouom.wekit.loader.core

import moe.ouom.wekit.core.bridge.HookFactoryBridge.registerDelegate
import moe.ouom.wekit.hooks.core.HookItemLoader
import moe.ouom.wekit.hooks.core.factory.HookItemFactory
import moe.ouom.wekit.utils.log.WeLogger.i

object HooksLoader {
    private const val TAG = "HooksLoader"

    @JvmStatic
    fun load(processType: Int) {
        i(TAG, "loading hooks...")
        val hookItemLoader = HookItemLoader()
        hookItemLoader.loadHookItem(processType)
        val factory = HookItemFactory.INSTANCE
        registerDelegate(factory)
        i(TAG, "hooks loaded success")
    }
}