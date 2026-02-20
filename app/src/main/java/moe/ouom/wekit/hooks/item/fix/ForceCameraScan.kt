package moe.ouom.wekit.hooks.item.fix

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/扫码增强", desc = "强制使用相机扫码方式处理所有扫码")
class ForceCameraScan : BaseSwitchFunctionHookItem(), IDexFind {

    private val methodHandleScan by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodHandleScan.find(dexKit, descriptors = descriptors) {
            matcher {
                usingStrings(
                    "MicroMsg.QBarStringHandler",
                    "key_offline_scan_show_tips"
                )
            }
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        methodHandleScan.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    try {
                        // 确保参数足够
                        if (param.args.size < 4) return@beforeIfEnabled

                        val arg2 = param.args[2] as? Int ?: return@beforeIfEnabled
                        val arg3 = param.args[3] as? Int ?: return@beforeIfEnabled

                        // 相机扫码的值
                        val CAMERA_VALUE_1 = 0
                        val CAMERA_VALUE_2 = 4

                        val BLOCKED_PAIR_1 = Pair(1, 34)   // 相册扫码
                        val BLOCKED_PAIR_2 = Pair(4, 37)   // 长按扫码

                        val currentPair = Pair(arg2, arg3)

                        // 如果是相册或长按扫码，强制改成相机扫码的值
                        if (currentPair == BLOCKED_PAIR_1 || currentPair == BLOCKED_PAIR_2) {
                            param.args[2] = CAMERA_VALUE_1
                            param.args[3] = CAMERA_VALUE_2
                        }
                    } catch (_: Exception) {
                        // 忽略异常
                    }
                }
            }
        }
    }
}