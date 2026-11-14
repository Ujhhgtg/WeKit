package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import android.app.Activity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.WeakHashMap

/**
 * Optional coordination between the replacement bar and the conversation home, without making
 * either feature depend on the other's Dex targets. All callers run on the UI thread.
 */
object LauncherNavigation {
    private val nativeTabs = listOf(TAB_HOME, TAB_CONTACTS, TAB_DISCOVER, TAB_ME)
    private val barOverrides = WeakHashMap<Activity, MutableState<Boolean?>>()

    var tabsProvider: ((Any) -> List<Int>)? = null
    var onTabsChanged: ((Any) -> Unit)? = null
    var applyBarVisibility: ((Activity) -> Boolean)? = null

    /** Pager positions mapped to WeChat's logical tab IDs, including unmanaged native pagers. */
    fun tabsOf(adapter: Any): List<Int> = tabsProvider?.invoke(adapter) ?: nativeTabs

    fun notifyTabsChanged(adapter: Any) {
        onTabsChanged?.invoke(adapter)
    }

    /** null: use the bar's own rules; true: hide on home; false: show on another tab. */
    fun barOverride(activity: Activity): State<Boolean?> = overrideState(activity)

    fun setBarOverride(activity: Activity, hidden: Boolean?) {
        overrideState(activity).value = hidden
    }

    /** Returns false when no replacement bar owns this activity's bottom container. */
    fun syncBar(activity: Activity): Boolean = applyBarVisibility?.invoke(activity) == true

    private fun overrideState(activity: Activity): MutableState<Boolean?> =
        barOverrides.getOrPut(activity) { mutableStateOf(null) }
}
