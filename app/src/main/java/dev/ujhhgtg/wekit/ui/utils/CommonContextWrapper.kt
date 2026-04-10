package dev.ujhhgtg.wekit.ui.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import dev.ujhhgtg.wekit.utils.ModuleRes

/**
 * 为解决 Xposed 模块 UI 注入时的环境冲突设计
 *
 * 它可以：
 * 1. 资源代理：将 Resources/Theme 代理到 ModuleRes，确保能正确加载模块内的 Layout 和 Style
 * 2. ClassLoader 统一：重写 getClassLoader() 返回模块原本的加载器，而非 createPackageContext 生成的副本
 */
class CommonContextWrapper private constructor(base: Context?): ContextWrapper(base) {

    private val mTheme: Resources.Theme
    private val mResources: Resources = ModuleRes.moduleContext!!.resources

    init {
        this.mTheme = this.mResources.newTheme()
    }

    override fun getClassLoader(): ClassLoader {
        return javaClass.classLoader!!
    }

    override fun getResources(): Resources {
        return mResources
    }

    override fun getAssets(): AssetManager? {
        return mResources.assets
    }

    override fun getTheme(): Resources.Theme {
        return mTheme
    }

    override fun setTheme(resid: Int) {
        mTheme.applyStyle(resid, true)
    }

    companion object {
        fun create(base: Context): Context {
            if (ModuleRes.moduleContext == null) {
                return base
            }
            return CommonContextWrapper(base)
        }
    }
}
