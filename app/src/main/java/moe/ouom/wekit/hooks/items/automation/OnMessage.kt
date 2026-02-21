package moe.ouom.wekit.hooks.items.automation

import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "自动化/触发器：收到消息", desc = "收到消息时是否执行 onMessage()")
object OnMessage : BaseSwitchFunctionHookItem()