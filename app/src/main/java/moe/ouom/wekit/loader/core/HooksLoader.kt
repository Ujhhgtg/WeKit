package moe.ouom.wekit.loader.core

import moe.ouom.wekit.core.bridge.HookFactoryBridge.registerDelegate
import moe.ouom.wekit.hooks.core.HookItemLoader
import moe.ouom.wekit.hooks.core.factory.HookItemFactory
import moe.ouom.wekit.utils.log.WeLogger

object HooksLoader {
    private const val TAG = "HooksLoader"

    @JvmStatic
    fun load(processType: Int) {
        WeLogger.i(TAG, "loading hooks...")
        val hookItemLoader = HookItemLoader()
        hookItemLoader.loadHookItem(processType)
        val factory = HookItemFactory.INSTANCE
        registerDelegate(factory)
        WeLogger.i(TAG, "hooks loaded success")
    }
}