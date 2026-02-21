package moe.ouom.wekit.hooks.items.automation

import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "自动化/触发器：发起请求", desc = "发起请求时是否执行 onRequest()")
object OnRequest : BaseSwitchFunctionHookItem()