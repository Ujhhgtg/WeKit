package moe.ouom.wekit.hooks.items.enhance

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/强制平板模式", desc = "让应用将当前设备识别为平板")
object ForceTabletMode : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodIsTablet by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodIsTablet.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    param.result = true
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodIsTablet.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("Lenovo TB-9707F", "eebbk")
            }
        }

        return descriptors
    }
}