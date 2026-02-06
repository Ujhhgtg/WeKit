package moe.ouom.wekit.loader;

public class TransitClassLoader extends ClassLoader {

    // BootClassLoader，只包含 Android 系统核心类
    private static final ClassLoader sSystem = ClassLoader.getSystemClassLoader().getParent();
    // 模块的 ClassLoader
    private static final ClassLoader sModule = TransitClassLoader.class.getClassLoader();

    public TransitClassLoader() {
        // 父加载器设置为 sSystem (BootClassLoader)
        // 意味着，除了系统类,其他所有类默认都找不到，除非我们在下面显式处理
        super(sSystem);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Kotlin、Xposed 和第三方库相关，走 Module 加载器
        if (name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.") ||
                name.startsWith("de.robv.android.xposed.") ||
                name.startsWith("io.github.libxposed.") ||
                name.startsWith("org.luckypray.dexkit.") ||
                name.startsWith("com.afollestad.materialdialogs.") ||
                name.startsWith("com.tencent.mmkv.")) {
            try {
                assert sModule != null;
                return sModule.loadClass(name);
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }

        if (name.startsWith("moe.ouom.wekit.")) {
            // 区分哪些走 Hidden DEX,哪些走 Main DEX
            if (name.startsWith("moe.ouom.wekit.hooks.")) {
                if (name.startsWith("moe.ouom.wekit.hooks.base.")) {
                    try {
                        assert sModule != null;
                        return sModule.loadClass(name);
                    } catch (ClassNotFoundException e) {
                        // ignore, fallback to default behavior (which will likely fail too but correct logic)
                    }
                }

            } else {
                // 其他 moe.ouom.wekit.* 全部走主 DEX
                try {
                    assert sModule != null;
                    return sModule.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // ignore
                }
            }
        }

        return super.loadClass(name, resolve);
    }
}
