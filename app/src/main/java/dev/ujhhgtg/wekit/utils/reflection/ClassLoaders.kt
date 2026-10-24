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

    inline val HYBRID: ClassLoader
        get() = HybridClassLoader
}
