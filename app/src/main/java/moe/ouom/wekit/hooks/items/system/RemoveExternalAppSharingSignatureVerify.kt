package moe.ouom.wekit.hooks.items.system

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/移除分享签名校验", desc = "移除第三方应用分享到微信的签名校验")
object RemoveExternalAppSharingSignatureVerify : SwitchHookItem(), IResolvesDex {

    private val methodSignCheck by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()
        methodSignCheck.find(dexKit, descriptors = descriptors) {
            searchPackages("com.tencent.mm.pluginsdk.model.app")
            matcher {
                usingEqStrings("checkAppSignature get local signature failed")
            }
        }
        return descriptors
    }

    override fun onEnable() {
        methodSignCheck.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = true
                }
            }
        }
    }
}
