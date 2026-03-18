package moe.ouom.wekit.hooks.items.beautify

import android.view.View
import android.widget.AbsListView
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.items.chat.ConversationGrouping
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.invokeOriginal

@HookItem(path = "界面美化/隐藏主页下滑「最近」页", desc = "禁用主页下滑功能")
object HideHomeScreenSwipeDownPage : SwitchHookItem() {

    override fun onEnable() {
        "android.widget.ListView".toClass().asResolver()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore { param ->
                if (param.thisObject.javaClass.simpleName != "ConversationListView") return@hookBefore
                val view = param.args[0] as View
                val className = view.javaClass.simpleName
                if (className == "TaskBarContainer") {
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, heightPx)
                    }
                    param.invokeOriginal(args = arrayOf(spacer, null, true))
                    param.result = null
                }
            }
    }
}
