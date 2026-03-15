package moe.ouom.wekit.hooks.api.ui

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "API/微信主屏幕美化服务", desc = "为其他功能提供美化微信主屏幕的能力")
object WeMainActivityBeautifyApi : ApiHookItem(), IResolvesDex {

    val methodDoOnCreate by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodDoOnCreate.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.MainTabUI"
                usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
            }
        }

        return descriptors
    }
}
