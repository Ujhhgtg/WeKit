package dev.ujhhgtg.wekit.dexkit.abc

import dev.ujhhgtg.wekit.dexkit.dsl.DexDelegateBase
import org.luckypray.dexkit.DexKitBridge

/**
 * 表示一个需要通过 DexKit 查找符号的 HookItem。
 *
 * 实现类通过 [dev.ujhhgtg.wekit.dexkit.dsl.dexClass] / [dev.ujhhgtg.wekit.dexkit.dsl.dexMethod] / [dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor] 工厂函数声明委托属性，
 * 这些委托在构造时自动注册到 [dexDelegates]。
 */
interface IResolvesDex {

    /**
     * 当前 HookItem 持有的所有 Dex 委托。
     * 由 BaseHookItem 维护，由委托工厂函数自动填充。
     */
    val dexDelegates: List<DexDelegateBase>

    /**
     * 执行 DexKit 查找，将结果写入各委托自身。
     * 调用方通过 [collectDescriptors] 读取结果后持久化。
     */
    fun resolveDex(dexKit: DexKitBridge)

    /**
     * 将所有委托的当前状态收集为 key → descriptor 字符串映射，
     * 供 [dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager] 持久化。
     */
    fun collectDescriptors(): Map<String, String> =
        dexDelegates.associate { it.key to (it.getDescriptorString() ?: "") }

    /**
     * 从缓存 Map 中逐个恢复委托状态。
     * 每个委托独立加载，某个 key 缺失不会影响其他委托。
     *
     * @return 缓存中不存在或值为空的 key 集合（需要重新 DexKit 扫描的委托）
     */
    fun loadFromCache(cache: Map<String, Any>): Set<String> {
        val missingKeys = mutableSetOf<String>()
        for (delegate in dexDelegates) {
            val value = cache[delegate.key] as? String
            if (!value.isNullOrEmpty()) {
                delegate.loadDescriptor(value)
            } else {
                missingKeys += delegate.key
            }
        }
        return missingKeys
    }
}
