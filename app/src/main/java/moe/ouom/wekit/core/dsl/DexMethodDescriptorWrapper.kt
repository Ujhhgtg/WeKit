package moe.ouom.wekit.core.dsl

import moe.ouom.wekit.dexkit.DexMethodDescriptor
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Dex 方法描述符包装类
 * 支持懒加载和 DSL 语法
 */
class DexMethodDescriptorWrapper(
    private val key: String,
    private val hookItem: Any? = null  // 可选的 HookItem 实例
) {
    private var descriptor: DexMethodDescriptor? = null
    private var method: Method? = null

    /**
     * 设置描述符
     */
    fun setDescriptor(desc: DexMethodDescriptor) {
        this.descriptor = desc
    }

    /**
     * 从缓存字符串恢复描述符
     */
    fun setDescriptorFromCache(descriptorString: String) {
        try {
            this.descriptor = DexMethodDescriptor(descriptorString)
        } catch (e: Exception) {
            throw RuntimeException("Failed to restore descriptor from cache for key: $key", e)
        }
    }

    /**
     * 获取描述符
     */
    fun getDescriptor(): DexMethodDescriptor? = descriptor

    /**
     * 获取描述符字符串（用于缓存）
     */
    fun getDescriptorString(): String? = descriptor?.getDescriptor()

    /**
     * 获取 Method 实例
     */
    fun getMethod(classLoader: ClassLoader): Method? {
        if (method == null && descriptor != null) {
            try {
                method = descriptor!!.getMethodInstance(classLoader)
            } catch (e: NoSuchMethodException) {
                throw RuntimeException("Failed to get method instance for $key: ${descriptor!!.getDescriptor()}", e)
            }
        }
        return method
    }

    /**
     * 查找 Dex 方法
     */
    fun findDexClassMethod(
        dexKit: DexKitBridge,
        block: FindMethod.() -> Unit
    ) {
        val results = dexKit.findMethod(block).toList()

        if (results.isEmpty()) {
            throw RuntimeException("DexKit: No method found for key: $key")
        }

        if (results.size > 1) {
            throw RuntimeException("DexKit: Multiple methods found for key: $key, count: ${results.size}")
        }

        val methodData = results[0]
        val desc = DexMethodDescriptor(
            methodData.className,
            methodData.methodName,
            methodData.methodSign
        )
        setDescriptor(desc)
    }

    /**
     * 查找 Dex 方法（可选模式）
     * @param allowMultiple 是否允许多个结果（取第一个）
     * @param allowEmpty 是否允许空结果
     */
    fun findDexClassMethodOptional(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowEmpty: Boolean = false,
        block: FindMethod.() -> Unit
    ): Boolean {
        val results = dexKit.findMethod(block).toList()

        if (results.isEmpty()) {
            if (!allowEmpty) {
                throw RuntimeException("DexKit: No method found for key: $key")
            }
            return false
        }

        if (results.size > 1 && !allowMultiple) {
            throw RuntimeException("DexKit: Multiple methods found for key: $key, count: ${results.size}")
        }

        val methodData = results[0]
        val desc = DexMethodDescriptor(
            methodData.className,
            methodData.methodName,
            methodData.methodSign
        )
        setDescriptor(desc)
        return true
    }

    /**
     * DSL: 转换为可 Hook 的方法
     */
    fun toDexMethod(
        classLoader: ClassLoader,
        block: DexMethodHookBuilder.() -> Unit
    ) {
        toDexMethod(classLoader, null, block)
    }

    fun toDexMethod(
        classLoader: ClassLoader,
        priority: Int?,
        block: DexMethodHookBuilder.() -> Unit
    ) {
        val method = getMethod(classLoader)
            ?: throw RuntimeException("Method not found for key: $key, descriptor: ${descriptor?.getDescriptor()}")

        val builder = DexMethodHookBuilder(method, priority, hookItem)
        builder.block()
        builder.execute()
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
            wrapper = DexMethodDescriptorWrapper(key, thisRef)
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
