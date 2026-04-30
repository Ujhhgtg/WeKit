package dev.ujhhgtg.wekit.hooks.items.profile

import android.graphics.Bitmap
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "个人资料/上传透明头像", description = "头像上传时使用 PNG 格式保持透明")
object UploadTransparentAvatars : SwitchHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName

    private val methodSaveBitmap by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodSaveBitmap.find(dexKit) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                usingStrings("saveBitmapToImage pathName null or nil", "MicroMsg.BitmapUtil")
            }
        }
    }

    override fun onEnable() {
        methodSaveBitmap.hookBefore {
            val args = args

            val pathName = args[3] as? String
            if (pathName != null &&
                (pathName.contains("avatar") || pathName.contains("user_hd"))
            ) {
                WeLogger.i(TAG, "检测到头像保存: $pathName")
                args[2] = Bitmap.CompressFormat.PNG
                WeLogger.i(TAG, "已将头像格式修改为PNG，保留透明通道")
            }
        }
    }
}
