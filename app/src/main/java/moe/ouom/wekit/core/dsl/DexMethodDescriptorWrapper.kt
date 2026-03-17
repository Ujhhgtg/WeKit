package moe.ouom.wekit.core.dsl

import moe.ouom.wekit.dexkit.DexMethodDescriptor
import java.lang.reflect.Method
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Dex 方法描述符包装类
 * 支持懒加载和 DSL 语法
 */
class DexMethodDescriptorWrapper(
    private val key: String
) {
    private var descriptor: DexMethodDescriptor? = null
    private var method: Method? = null

    /**
     * 获取 Method 实例
     */
    fun getMethod(classLoader: ClassLoader): Method? {
        if (method == null && descriptor != null) {
            try {
                method = descriptor!!.getMethodInstance(classLoader)
            } catch (e: NoSuchMethodException) {
                throw RuntimeException(
                    "Failed to get method instance for $key: ${descriptor!!.descriptor}",
                    e
                )
            }
        }
        return method
    }
}

/**
 * 懒加载 Dex 方法委托属性
 */
class LazyDexMethodDelegate(
    private val key: String
) : ReadOnlyProperty<Any?, DexMethodDescriptorWrapper> {

    private var wrapper: DexMethodDescriptorWrapper? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): DexMethodDescriptorWrapper {
        if (wrapper == null) {
            // 传递 thisRef 作为 hookItem，这样可以在 hook 时检查启用状态
            wrapper = DexMethodDescriptorWrapper(key)
        }
        return wrapper!!
    }
}

/**
 * DSL: 创建懒加载 Dex 方法
 */
fun lazyDexMethod(key: String): LazyDexMethodDelegate {
    return LazyDexMethodDelegate(key)
}
