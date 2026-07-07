package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Modifier

@Feature(
    name = "解除单个表情数量上限",
    categories = ["聊天"],
    description = "解除表情面板「添加的单个表情」分类的 999 个数量上限, 允许无限添加图片表情"
)
object UnlockCustomEmojiLimit : SwitchFeature(), IResolveDex {

    // 微信通过 gr.z.a() 读取配置项 CustomEmojiMaxSize (默认 999) 作为单个表情上限,
    // gr.v.a() 中以 `size >= z.a()` 计算 custom_full 标志位, 添加表情时据此拦截.
    // 将该方法返回值改为 Int.MAX_VALUE 即可使 custom_full 恒为 false, 解除上限.
    private val methodGetCustomEmojiMaxSize by dexMethod {
        matcher {
            usingEqStrings("CustomEmojiMaxSize")
            returnType(Int::class.java)
            paramCount(0)
            modifiers = Modifier.STATIC
        }
    }

    override fun onEnable() {
        methodGetCustomEmojiMaxSize.hookBefore {
            result = Int.MAX_VALUE
        }
    }
}
