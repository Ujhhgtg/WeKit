package dev.ujhhgtg.wekit.hooks.items.beautify

import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.hooks.items.chat.ConversationGrouping
import dev.ujhhgtg.wekit.utils.invokeOriginal
import dev.ujhhgtg.wekit.utils.reflection.resolve

@HookItem(path = "界面美化/隐藏主页下滑「最近」页", description = "禁用主页下滑功能")
object HideHomeScreenSwipeDownPage : SwitchHookItem() {

    override fun onEnable() {
        ListView::class.resolve()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore {
                if (thisObject.javaClass.simpleName != "ConversationListView") return@hookBefore
                val view = args[0] as View
                val className = view.javaClass.simpleName
                if (className == "TaskBarContainer") {
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, heightPx)
                    }
                    invokeOriginal(args = arrayOf(spacer, null, true))
                    result = null
                }
            }
    }
}
