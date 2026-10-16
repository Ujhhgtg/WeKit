package dev.ujhhgtg.wekit.constants

object PackageNames {

    const val WECHAT = "com.tencent.mm"
    const val MODULE = "dev.ujhhgtg.wekit"

    @Suppress("NOTHING_TO_INLINE")
    @JvmStatic
    inline fun isWeChat(packageName: String) = packageName.startsWith(WECHAT)
}
