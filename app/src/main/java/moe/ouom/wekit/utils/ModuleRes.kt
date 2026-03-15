package moe.ouom.wekit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.logging.WeLogger

/**
 * 模块资源加载器助手
 * 用于简化 Xposed 模块加载自身资源（布局、图片、字符串）的流程
 */
@SuppressLint("StaticFieldLeak")
object ModuleRes {

    private val TAG = nameof(ModuleRes)

    var moduleContext: Context? = null
    var resources: Resources? = null
    private lateinit var packageName: String

    /**
     * 初始化加载器，只需在 Hook 入口处调用一次
     *
     * @param hostContext   宿主的 Context
     * @param modulePkgName 模块的包名
     */
    @SuppressLint("DiscouragedApi")
    fun init(hostContext: Context, modulePkgName: String) {
        if (moduleContext != null) return

        try {
            moduleContext = hostContext.createPackageContext(
                modulePkgName,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
            resources = moduleContext!!.resources
            packageName = modulePkgName

            val themeId = resources!!.getIdentifier("Theme.WeKit", "style", packageName)
            if (themeId != 0) {
                moduleContext!!.setTheme(themeId)
            } else {
                WeLogger.e(TAG, "theme not found! module ui might crash")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to initialize", e)
        }
    }

    @SuppressLint("DiscouragedApi")
    fun getId(resName: String, resType: String): Int {
        val res = resources ?: return 0
        val id = res.getIdentifier(resName, resType, packageName)
        if (id == 0) WeLogger.e(TAG, "resource $resType/$resName not found")
        return id
    }

    fun getString(resName: String): String {
        val id = getId(resName, "string")
        return if (id == 0) "" else resources!!.getString(id)
    }

    fun getColor(resName: String): Int {
        val id = getId(resName, "color")
        return if (id == 0) 0 else resources!!.getColor(id, null)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun getDrawable(resName: String): Drawable? {
        var id = getId(resName, "drawable")
        if (id == 0) id = getId(resName, "mipmap")
        return if (id == 0) null else resources!!.getDrawable(id, null)
    }

    fun getDimen(resName: String): Float {
        val id = getId(resName, "dimen")
        return if (id == 0) 0f else resources!!.getDimension(id)
    }
}