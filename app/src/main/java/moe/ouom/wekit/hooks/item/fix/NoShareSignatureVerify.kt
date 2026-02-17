package moe.ouom.wekit.hooks.item.fix

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.dsl.resultValue
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/移除分享签名校验", desc = "移除第三方应用分享到微信的签名校验")
class NoShareSignatureVerify : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodSignCheck by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()
        methodSignCheck.find(dexKit, descriptors = descriptors) {
            matcher {
                usingEqStrings("checkAppSignature get local signature failed")
            }
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        methodSignCheck.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.resultValue(true)
                }
            }
        }
    }
}
