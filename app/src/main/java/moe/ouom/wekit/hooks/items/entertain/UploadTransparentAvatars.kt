package moe.ouom.wekit.hooks.items.entertain

import android.graphics.Bitmap
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "娱乐/上传透明头像", desc = "头像上传时使用 PNG 格式保持透明")
object UploadTransparentAvatars : BaseSwitchFunctionHookItem(), IDexFind {

    private val methodSaveBitmap by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodSaveBitmap.find(dexKit, descriptors = descriptors) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingStrings("saveBitmapToImage pathName null or nil", "MicroMsg.BitmapUtil")
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        methodSaveBitmap.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    try {
                        val args = param.args

                        val pathName = args[3] as? String
                        if (pathName != null &&
                            (pathName.contains("avatar") || pathName.contains("user_hd"))
                        ) {
                            WeLogger.i("检测到头像保存: $pathName")
                            args[2] = Bitmap.CompressFormat.PNG
                            WeLogger.i("已将头像格式修改为PNG，保留透明通道")
                        }
                    } catch (e: Exception) {
                        WeLogger.e("头像格式修改失败: ${e.message}")
                    }
                }
            }
        }
    }
}