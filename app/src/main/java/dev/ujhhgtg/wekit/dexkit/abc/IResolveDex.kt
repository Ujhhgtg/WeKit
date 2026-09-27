package dev.ujhhgtg.wekit.dexkit.abc

import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import org.luckypray.dexkit.DexKitBridge

/**
 * 表示一个需要通过 DexKit 查找符号的 Feature。
 *
 * 实现类通过 [dev.ujhhgtg.wekit.dexkit.dsl.dexClass] / [dev.ujhhgtg.wekit.dexkit.dsl.dexMethod] / [dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor] 工厂函数声明委托属性，
 * 这些委托在构造时自动注册到 [dexDelegates]。
 */
interface IResolveDex {

    /**
     * 当前 Feature 持有的所有 Dex 委托。
     * 由 BaseFeature 维护，由委托工厂函数自动填充。
     */
    val dexDelegates: List<BaseDexDelegate>

    /**
     * 执行 DexKit 查找，将结果写入各委托自身。
     * 调用方通过 [collectDescriptors] 读取结果后持久化。
     */
    fun resolveDex(dexKit: DexKitBridge) {}

    /**
     * 将所有委托的解析结果收集为 key → descriptor 映射，
     * 供 [dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager] 持久化；`null` 表示预期内的解析失败。
     */
    fun collectDescriptors(): Map<String, String?> =
        dexDelegates.associate { delegate ->
            check(delegate.isSettled) { "Dex delegate ${delegate.key} is not resolved: ${delegate.state}" }
            delegate.key to delegate.getDescriptorString()
        }

    /**
     * 从缓存 Map 中逐个恢复委托状态。
     * 每个委托独立加载，某个 key 缺失不会影响其他委托。
     *
     * @return 缓存中不存在的 key 集合（需要重新 DexKit 扫描的委托）
     */
    fun loadFromCache(cache: Map<String, String?>): Set<String> {
        val missingKeys = mutableSetOf<String>()
        for (delegate in dexDelegates) {
            if (delegate.key in cache) {
                delegate.restore(cache[delegate.key])
            } else {
                missingKeys += delegate.key
            }
        }
        return missingKeys
    }
}
