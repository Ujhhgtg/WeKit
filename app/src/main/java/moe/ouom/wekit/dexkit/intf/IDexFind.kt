package moe.ouom.wekit.dexkit.intf

import moe.ouom.wekit.core.dsl.DexClassDelegate
import moe.ouom.wekit.core.dsl.DexMethodDelegate
import org.luckypray.dexkit.DexKitBridge

/**
 * Dex 查找接口
 * 实现此接口的 HookItem 将支持自包含的 Dex 方法查找
 */
interface IDexFind {
    /**
     * 执行 Dex 查找
     * @param dexKit DexKitBridge 实例
     * @return Map<Key, descriptor字符串>
     */
    fun dexFind(dexKit: DexKitBridge): Map<String, String>

    /**
     * 从缓存加载 descriptors（框架自动调用）
     * @param cache 缓存的 Map<Key, descriptor字符串>
     */
    fun loadFromCache(cache: Map<String, Any>) {
        // 自动收集所有 dex 开头的委托属性
        val delegates = collectDexDelegates()

        delegates.forEach { (key, delegate) ->
            val value = cache[key] as? String
            if (value != null) {
                when (delegate) {
                    is DexClassDelegate -> delegate.setDescriptor(value)
                    is DexMethodDelegate -> delegate.setDescriptorFromString(value)
                }
            }
        }
    }

    /**
     * 收集所有 dex 委托属性
     */
    fun collectDexDelegates(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val clazz = this::class.java

        // 遍历所有字段
        clazz.declaredFields.forEach { field ->
            if (field.name.startsWith("dex")) {
                try {
                    field.isAccessible = true
                    val value = field.get(this)
                    when (value) {
                        is DexClassDelegate -> result[value.key] = value
                        is DexMethodDelegate -> result[value.key] = value
                    }
                } catch (e: Exception) {
                    // 忽略无法访问的字段
                }
            }
        }

        return result
    }
}
