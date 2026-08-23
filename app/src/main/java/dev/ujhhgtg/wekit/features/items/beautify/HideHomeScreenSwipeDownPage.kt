package dev.ujhhgtg.wekit.features.items.beautify

import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.ConversationGrouping
import dev.ujhhgtg.wekit.utils.invokeOriginalMethod

@Feature(
    id = "隐藏主页下滑「最近」页",
    nameRes = "feature_hide_home_screen_swipe_down_page_name",
    categoryIds = [FeatureCategoryIds.BEAUTIFY],
    descriptionRes = "feature_hide_home_screen_swipe_down_page_description",
)
object HideHomeScreenSwipeDownPage : SwitchFeature() {

    override fun onEnable() {
        ListView::class.reflekt()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore {
                if (thisObject!!.javaClass.simpleName != "ConversationListView") return@hookBefore
                val view = args[0] as View
                val className = view.javaClass.simpleName
                if (className == "TaskBarContainer") {
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, heightPx)
                    }
                    invokeOriginalMethod(args = arrayOf(spacer, null, true))
                    result = null
                }
            }
    }
}
