package moe.ouom.wekit.hooks.items.scripting_js

import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem

@HookItem(path = "脚本/触发器：发起请求", desc = "发起请求时是否执行 onRequest()")
object OnRequest : SwitchHookItem()