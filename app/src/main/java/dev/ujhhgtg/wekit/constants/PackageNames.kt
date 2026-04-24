package dev.ujhhgtg.wekit.constants

import dev.ujhhgtg.wekit.BuildConfig

object PackageNames {

    const val WECHAT = "com.tencent.mm"
    const val THIS = BuildConfig.APPLICATION_ID

    @Suppress("NOTHING_TO_INLINE")
    @JvmStatic
    inline fun isWeChat(packageName: String) = packageName.startsWith(WECHAT)
}
