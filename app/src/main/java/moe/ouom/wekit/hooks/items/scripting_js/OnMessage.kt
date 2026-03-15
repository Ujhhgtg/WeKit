package moe.ouom.wekit.hooks.items.scripting_js

import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem

@HookItem(path = "脚本/触发器：收到消息", desc = "收到消息时是否执行 onMessage()")
object OnMessage : SwitchHookItem()