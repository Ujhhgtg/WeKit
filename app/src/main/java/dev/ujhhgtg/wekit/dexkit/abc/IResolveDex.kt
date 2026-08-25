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
     * 解析结果由图协调器收集诊断、依赖和有效指纹后统一持久化。
     */
    fun resolveDex(dexKit: DexKitBridge) {}

}
