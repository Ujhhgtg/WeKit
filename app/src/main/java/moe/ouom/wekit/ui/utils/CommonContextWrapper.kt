package moe.ouom.wekit.ui.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.log.WeLogger
import java.lang.reflect.Constructor

/**
 * 为解决 Xposed 模块 UI 注入时的环境冲突设计
 *
 *
 * 它可以：
 * 1. 资源代理：将 Resources/Theme 代理到 ModuleRes，确保能正确加载模块内的 Layout 和 Style
 * 2. ClassLoader 统一：重写 getClassLoader() 返回模块原本的加载器，而非 createPackageContext 生成的副本
 * 3. View 创建拦截：注入自定义 LayoutInflater Factory，强制 XML 中的控件由模块 ClassLoader 加载，
 * 解决宿主与模块之间的 "ClassCastException" 类隔离冲突问题。
 *
 *
 * UPDATE LOG:
 * 2025.1.19 - 移除了 Theme.setTo(baseTheme)，防止宿主资源 ID 污染模块 Theme
 * - 代理 getAssets() 以确保资源加载链路完整
 */
class CommonContextWrapper(base: Context?, themeResId: Int) : ContextWrapper(base) {
    private val mTheme: Resources.Theme
    private var mInflater: LayoutInflater? = null
    private val mResources: Resources
    private val mModuleContext: Context = ModuleRes.getContext()

    init {
        // 锁定资源：只用模块的资源
        this.mResources = mModuleContext.resources

        // 创建独立 Theme
        this.mTheme = this.mResources.newTheme()

        // 应用模块 Theme
        if (themeResId != 0) {
            this.mTheme.applyStyle(themeResId, true)
        } else {
            // 尝试自动获取默认 Theme
            val defaultTheme = getResourceIdSafe("Theme.WeKit", "style")
            if (defaultTheme != 0) {
                this.mTheme.applyStyle(defaultTheme, true)
            } else {
                WeLogger.w("CommonContextWrapper: Theme.WeKit not found!")
            }
        }
    }

    private fun getResourceIdSafe(name: String?, type: String?): Int {
        return try {
            ModuleRes.getId(name, type)
        } catch (e: Throwable) {
            0
        }
    }

    override fun getClassLoader(): ClassLoader? {
        return javaClass.classLoader // 使用模块 ClassLoader
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

    // =================================================================================
    // 修改 getSystemService
    // =================================================================================
    override fun getSystemService(name: String): Any? {
        if (LAYOUT_INFLATER_SERVICE == name) {
            if (mInflater == null) {
                // mInflater = new ModuleLayoutInflater(LayoutInflater.from(getBaseContext()), this);
                // 2026.1.19: 不能使用上面的写法，它使用宿主 Context 创建解析器，导致无法识别模块 ID

                // 必须使用模块 Context 创建原始 Inflater

                val moduleInflater = LayoutInflater.from(mModuleContext)

                // 然后 cloneInContext 传入 'this' (Wrapper)，将 Theme 和 Token 桥接回来
                // 再包裹我们的 ModuleLayoutInflater 以处理 ClassLoader 问题
                mInflater = ModuleLayoutInflater(moduleInflater, this)
            }
            return mInflater
        }
        return super.getSystemService(name)
    }

    // =================================================================================
    // Custom Inflater
    // =================================================================================
    private class ModuleLayoutInflater(original: LayoutInflater?, newContext: Context) :
        LayoutInflater(original, newContext) {
        init {
            // 设置 Factory2 拦截 View 创建
            factory2 = ModuleFactory(newContext.classLoader)
        }

        override fun cloneInContext(newContext: Context): LayoutInflater {
            return ModuleLayoutInflater(this, newContext)
        }

        @Throws(ClassNotFoundException::class)
        override fun onCreateView(name: String?, attrs: AttributeSet?): View? {
            for (prefix in androidPrefix) {
                try {
                    val view = createView(name, prefix, attrs)
                    if (view != null) return view
                } catch (_: ClassNotFoundException) { }
            }
            return super.onCreateView(name, attrs)
        }

        companion object {
            private val androidPrefix = arrayOf<String?>(
                "android.widget.",
                "android.webkit.",
                "android.app."
            )
        }
    }

    @JvmRecord
    private data class ModuleFactory(val mClassLoader: ClassLoader?) : LayoutInflater.Factory2 {
        override fun onCreateView(
            parent: View?,
            name: String,
            context: Context,
            attrs: AttributeSet
        ): View? {
            if (name.startsWith("android.")) {
                return null
            }
            return createView(name, context, attrs)
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
            return onCreateView(null, name, context, attrs)
        }

        fun createView(name: String?, context: Context?, attrs: AttributeSet?): View? {
            var constructor = sConstructorCache[name]
            try {
                if (constructor == null) {
                    val clazz = mClassLoader!!.loadClass(name)
                    constructor = clazz.asSubclass<View?>(View::class.java)
                        .getConstructor(Context::class.java, AttributeSet::class.java)
                    constructor.isAccessible = true
                    sConstructorCache[name] = constructor
                }
                return constructor.newInstance(context, attrs)
            } catch (e: Exception) {
                return null
            }
        }

        companion object {
            private val sConstructorCache = HashMap<String?, Constructor<out View?>?>()
        }
    }

    companion object {
        fun createAppCompatContext(base: Context): Context {
            if (ModuleRes.getContext() == null) {
                return base
            }
            val themeId = ModuleRes.getId("Theme.WeKit", "style")
            return CommonContextWrapper(base, themeId)
        }
    }
}