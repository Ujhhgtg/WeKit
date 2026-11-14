package dev.ujhhgtg.wekit.utils.reflection

import android.content.Context
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader

object ClassLoaders {
    inline val HOST: ClassLoader
        get() = ReflectionClassLoader.value!!

    inline val MODULE: ClassLoader
        get() = ClassLoaders.javaClass.classLoader!!

    inline val BOOT: ClassLoader
        get() = Context::class.java.classLoader!!

    inline val HYBRID_FOR_MODULE: ClassLoader
        get() = HybridClassLoader.INSTANCE

    /**
     * Host-first, module-fallback ClassLoader for APIs that must define or resolve classes visible
     * from both sides. Used as the `ProxyBuilder.parentClassLoader(...)` for classes that extend a
     * host base class while implementing module marker interfaces.
     */
    val HYBRID_FOR_HOST by lazy {
        object : ClassLoader(HOST) {
            override fun findClass(name: String): Class<*> = MODULE.loadClass(name)
        }
    }
}
