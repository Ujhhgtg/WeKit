package moe.ouom.wekit.hooks.items.automation

import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "自动化/触发器：收到响应", desc = "收到响应时是否执行 onResponse()")
object OnResponse : BaseSwitchFunctionHookItem()