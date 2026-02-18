package moe.ouom.wekit.hooks.item.automation

import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "自动化/触发器：收到消息", desc = "收到消息时是否执行 onMessage()")
class OnMessage : BaseSwitchFunctionHookItem() {
    companion object {
        var enabled = false
            private set
    }

    override fun entry(classLoader: ClassLoader) {
        enabled = true
    }

    override fun unload(classLoader: ClassLoader) {
        super.unload(classLoader)
        enabled = false
    }
}