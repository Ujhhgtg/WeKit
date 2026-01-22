package moe.ouom.wekit.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.util.HashMap;

import moe.ouom.wekit.util.common.ModuleRes;
import moe.ouom.wekit.util.log.WeLogger;

/**
 * 为解决 Xposed 模块 UI 注入时的环境冲突设计
 * <p>
 * 它可以：
 * 1. 资源代理：将 Resources/Theme 代理到 ModuleRes，确保能正确加载模块内的 Layout 和 Style
 * 2. ClassLoader 统一：重写 getClassLoader() 返回模块原本的加载器，而非 createPackageContext 生成的副本
 * 3. View 创建拦截：注入自定义 LayoutInflater Factory，强制 XML 中的控件由模块 ClassLoader 加载，
 * 解决宿主与模块之间的 "ClassCastException" 类隔离冲突问题。
 * <p>
 * UPDATE LOG:
 *      2025.1.19 - 移除了 Theme.setTo(baseTheme)，防止宿主资源 ID 污染模块 Theme
 *                - 代理 getAssets() 以确保资源加载链路完整
 */
public class CommonContextWrapper extends ContextWrapper {

    private final Resources.Theme mTheme;
    private LayoutInflater mInflater;
    private final Resources mResources;
    private final Context mModuleContext;

    public CommonContextWrapper(Context base, int themeResId) {
        super(base);

        mModuleContext = ModuleRes.getContext();

        if (mModuleContext == null) {
            throw new IllegalStateException("CommonContextWrapper: ModuleRes is NOT initialized!");
        }

        // 锁定资源：只用模块的资源
        this.mResources = mModuleContext.getResources();

        // 创建独立 Theme
        this.mTheme = this.mResources.newTheme();

        // 应用模块 Theme
        if (themeResId != 0) {
            this.mTheme.applyStyle(themeResId, true);
        } else {
            // 尝试自动获取默认 Theme
            int defaultTheme = getResourceIdSafe("Theme.WeKit", "style");
            if (defaultTheme != 0) {
                this.mTheme.applyStyle(defaultTheme, true);
            } else {
                WeLogger.w("CommonContextWrapper: Theme.WeKit not found!");
            }
        }
    }

    private int getResourceIdSafe(String name, String type) {
        try {
            return ModuleRes.getId(name, type);
        } catch (Throwable e) {
            return 0;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader(); // 使用模块 ClassLoader
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public AssetManager getAssets() {
        return mResources.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        return mTheme;
    }

    @Override
    public void setTheme(int resid) {
        mTheme.applyStyle(resid, true);
    }

    // =================================================================================
    // 修改 getSystemService
    // =================================================================================
    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (mInflater == null) {
                // mInflater = new ModuleLayoutInflater(LayoutInflater.from(getBaseContext()), this);
                // 2026.1.19: 不能使用上面的写法，它使用宿主 Context 创建解析器，导致无法识别模块 ID

                // 必须使用模块 Context 创建原始 Inflater
                LayoutInflater moduleInflater = LayoutInflater.from(mModuleContext);

                // 然后 cloneInContext 传入 'this' (Wrapper)，将 Theme 和 Token 桥接回来
                // 再包裹我们的 ModuleLayoutInflater 以处理 ClassLoader 问题
                mInflater = new ModuleLayoutInflater(moduleInflater, this);
            }
            return mInflater;
        }
        return super.getSystemService(name);
    }

    public static Context createAppCompatContext(@NonNull Context base) {
        if (ModuleRes.getContext() == null) {
            return base;
        }
        int themeId = ModuleRes.getId("Theme.WeKit", "style");
        return new CommonContextWrapper(base, themeId);
    }

    // =================================================================================
    // Custom Inflater
    // =================================================================================

    private static class ModuleLayoutInflater extends LayoutInflater {
        private static final String[] sAndroidPrefix = {
                "android.widget.",
                "android.webkit.",
                "android.app."
        };

        protected ModuleLayoutInflater(LayoutInflater original, Context newContext) {
            super(original, newContext);
            // 设置 Factory2 拦截 View 创建
            setFactory2(new ModuleFactory(newContext.getClassLoader()));
        }

        @Override
        public LayoutInflater cloneInContext(Context newContext) {
            return new ModuleLayoutInflater(this, newContext);
        }

        @Override
        protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
            for (String prefix : sAndroidPrefix) {
                try {
                    View view = createView(name, prefix, attrs);
                    if (view != null) return view;
                } catch (ClassNotFoundException ignored) { }
            }
            return super.onCreateView(name, attrs);
        }
    }

    private static class ModuleFactory implements LayoutInflater.Factory2 {
        private final ClassLoader mClassLoader;
        private static final HashMap<String, Constructor<? extends View>> sConstructorCache = new HashMap<>();

        public ModuleFactory(ClassLoader cl) {
            this.mClassLoader = cl;
        }

        @Nullable
        @Override
        public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
            if (name.startsWith("android.")) {
                return null;
            }
            return createView(name, context, attrs);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
            return onCreateView(null, name, context, attrs);
        }

        private View createView(String name, Context context, AttributeSet attrs) {
            Constructor<? extends View> constructor = sConstructorCache.get(name);
            try {
                if (constructor == null) {
                    Class<?> clazz = mClassLoader.loadClass(name);
                    constructor = clazz.asSubclass(View.class).getConstructor(Context.class, AttributeSet.class);
                    constructor.setAccessible(true);
                    sConstructorCache.put(name, constructor);
                }
                return constructor.newInstance(context, attrs);
            } catch (Exception e) {
                return null;
            }
        }
    }
}