package dev.ujhhgtg.wekit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.constants.PackageNames

/**
 * 模块资源加载器助手
 * 用于简化 Xposed 模块加载自身资源（布局、图片、字符串）的流程
 */
@SuppressLint("StaticFieldLeak")
object ModuleRes {

    private val TAG = nameOf(ModuleRes)

    var moduleContext: Context? = null
    var resources: Resources? = null

    @SuppressLint("DiscouragedApi")
    fun init(hostContext: Context, modulePkgName: String) {
        if (moduleContext != null) return

        runCatching {
            moduleContext = hostContext.createPackageContext(
                PackageNames.THIS,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
            resources = moduleContext!!.resources
        }.onFailure { WeLogger.e(TAG, "failed to initialize module resources", it) }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun getDrawable(resId: Int): Drawable? {
        return resources!!.getDrawable(resId, null)
    }
}
