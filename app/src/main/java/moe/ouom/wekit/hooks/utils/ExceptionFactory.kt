package moe.ouom.wekit.hooks.utils

import de.robv.android.xposed.XposedBridge
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.utils.logging.LogUtils

object ExceptionFactory {

    private val exceptionMap = HashMap<BaseHookItem, MutableList<Throwable>>()

    private fun check(item: BaseHookItem, throwable: Throwable): Boolean {
        val list = exceptionMap[item] ?: return false
        if (list.size < 3) return false
        return list.any { it.message == throwable.message }
    }

    fun add(item: BaseHookItem?, throwable: Throwable) {
        if (item == null) return
        if (check(item, throwable)) return
        exceptionMap.getOrPut(item) { mutableListOf() }.add(0, throwable)
        XposedBridge.log(throwable)
        try {
            LogUtils.addError("item_${item.itemName}", throwable)
        } catch (_: NoClassDefFoundError) {
        }
    }
}
