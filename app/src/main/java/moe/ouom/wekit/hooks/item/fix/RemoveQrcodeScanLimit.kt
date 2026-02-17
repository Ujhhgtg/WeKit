package moe.ouom.wekit.hooks.item.fix

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "优化与修复/移除二维码扫描限制", desc = "移除长按图片与相册选择的二维码扫描限制")
class RemoveQrcodeScanLimit : BaseSwitchFunctionHookItem(), IDexFind {
    enum class ScanScene(val source: Int, val a8KeyScene: Int) {
        CAMERA(0, 4), // 相机扫描
        ALBUM(1, 34), // 相册选择
        PICTURE_LONG_PRESS(4, 37) // 长按图片
    }

    private val methodQBarString by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()
        methodQBarString.find(dexKit, descriptors = descriptors) {
            matcher {
                usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
            }
        }
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        methodQBarString.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val source = param.args[2] as Int
                    val a8KeyScene = param.args[3] as Int
                    val matchedScene = ScanScene.entries.find { it.source == source && it.a8KeyScene == a8KeyScene }
                    if (matchedScene == ScanScene.ALBUM || matchedScene == ScanScene.PICTURE_LONG_PRESS) {
                        param.args[2] = ScanScene.CAMERA.source
                        param.args[3] = ScanScene.CAMERA.a8KeyScene
                    }
                }
            }
        }
    }
}
