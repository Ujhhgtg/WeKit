package moe.ouom.wekit.hooks.utils

import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.hooks.gen.HookItemEntryList

object HookItemFactory {

    // 使用 LinkedHashMap 保持 KSP 生成的顺序
    private val ITEM_MAP: Map<Class<out BaseHookItem>, BaseHookItem> =
        LinkedHashMap<Class<out BaseHookItem>, BaseHookItem>().apply {
            HookItemEntryList.getAllHookItems().forEach { put(it.javaClass, it) }
        }

    fun getItems() = ITEM_MAP.values.toList()
}
