package moe.ouom.wekit.hooks.items.scripting_js

import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem

@HookItem(path = "脚本/触发器：收到响应", desc = "收到响应时是否执行 onResponse()")
object OnResponse : SwitchHookItem()