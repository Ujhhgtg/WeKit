package dev.ujhhgtg.wekit.features.items.beautify.home

import android.annotation.SuppressLint
import android.app.Activity
import android.util.SparseArray
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.util.size
import androidx.lifecycle.Lifecycle
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.LauncherNavigation
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_CONTACTS
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_DISCOVER
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_ME
import dev.ujhhgtg.wekit.i18n.localizedString
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

data class HomeMenuItem(val id: Int, val title: String, val click: () -> Unit)

class HomeSession(
    val mainUI: Any,
    val activity: Activity,
    private val originalRoot: ViewGroup,
    private val originalFrame: FrameLayout,
) {
    private val nativeList =
        originalRoot.allViews.first {
            it.hasHostClass("com.tencent.mm.ui.conversation.ConversationListView") ||
                it.hasHostClass("com.tencent.mm.ui.conversation.recycler.ConversationRecyclerView")
        } as ViewGroup
    private val originalListParent = nativeList.parent as ViewGroup
    private val originalListIndex = originalListParent.indexOfChild(nativeList)
    private val originalListParams = nativeList.layoutParams
    private val originalListSaveEnabled = nativeList.isSaveFromParentEnabled
    private val originalRootVisibility = originalRoot.visibility
    private val originalPadding =
        intArrayOf(
            nativeList.paddingLeft,
            nativeList.paddingTop,
            nativeList.paddingRight,
            nativeList.paddingBottom,
        )
    private val owners = LifecycleOwnerProvider.getOrCreate(activity)
    private val content = RelativeLayout(activity)
    private val shell = ComposeView(activity)
    private val statusBar = HomeStatusBar(activity, shell)
    private val hiddenViews = IdentityHashMap<View, Int>()
    private val chromeSizes = IdentityHashMap<View, ChromeSize>()

    @SuppressLint("DiscouragedApi")
    private val chromeIds =
        listOf("action_bar_container", "action_bar", "actionbar")
            .map { activity.resources.getIdentifier(it, "id", activity.packageName) }
            .filter { it != 0 }
            .toSet()
    private var attached = false
    private var disposed = false
    private var laidOut = false
    private var statusBarFailed = false
    var conversationSelected by mutableStateOf(true)
    var navigationTabs by mutableStateOf(listOf(TAB_CONTACTS, TAB_DISCOVER, TAB_ME))
    private var resumed by mutableStateOf(owners.lifecycle.currentState == Lifecycle.State.RESUMED)
    private var homeVisible by mutableStateOf(true)
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (BeautifyHome.isActive && !disposed) applyLayout()
    }
    private val watchdog = Runnable {
        if (BeautifyHome.isActive && !disposed && homeVisible && !laidOut) {
            WeLogger.e(
                "BeautifyHome",
                "Home did not lay out: shell=${shell.width}x${shell.height}, list=${nativeList.width}x${nativeList.height}",
            )
            BeautifyHome.detach(mainUI)
        }
    }

    fun attach() {
        shell.setLifecycleOwner(owners)
        shell.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        originalListParent.removeView(nativeList)
        content.addView(nativeList, RelativeLayout.LayoutParams(-1, -1))
        originalRoot.visibility = View.INVISIBLE
        shell.setContent {
            InjectedUiTheme {
                HomeContent(
                    content = content,
                    active = homeVisible && resumed,
                    statusBarTopPx = statusBar.topInsetPx,
                    navigationTabs = navigationTabs,
                    onNavigate = { page -> action { BeautifyHome.navigate(activity, page) } },
                    onSearch = { action { openSearch() } },
                    onBackgroundLongPress = { HomeWallpaper.showPicker(activity) },
                    menuItems = ::readMenu,
                )
            }
        }
        originalFrame.addView(
            shell,
            originalFrame.indexOfChild(originalRoot) + 1,
            FrameLayout.LayoutParams(originalRoot.layoutParams),
        )
        attached = true
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        applyLayout()
        shell.postDelayed(watchdog, 2500L)
    }

    fun applyLayout() {
        if (!attached || disposed) return
        val launcherForeground = (activity as LauncherUI).currentFragmet == null
        homeVisible = conversationSelected && launcherForeground
        LauncherNavigation.setBarOverride(activity, if (launcherForeground) homeVisible else null)
        val replacementOwnsBar = LauncherNavigation.syncBar(activity)
        if (!laidOut) {
            if (
                shell.width <= 0 ||
                    shell.height <= 0 ||
                    nativeList.parent == null ||
                    nativeList.width <= 0 ||
                    nativeList.height <= 0
            )
                return
            laidOut = true
        }
        if (originalRoot.visibility != View.INVISIBLE) originalRoot.visibility = View.INVISIBLE
        if (!statusBarFailed) {
            try {
                statusBar.apply(homeVisible && resumed)
            } catch (error: Exception) {
                statusBarFailed = true
                restoreStatusBar()
                WeLogger.e("BeautifyHome", "Unable to apply transparent status bar", error)
            }
        }

        val chrome = buildSet {
            viewField(BeautifyHome.fieldMainStatusMask, mainUI)?.let(::add)
            val fields =
                if (
                    nativeList.hasHostClass("com.tencent.mm.ui.conversation.ConversationListView")
                ) {
                    listOf(BeautifyHome.fieldListActionBar, BeautifyHome.fieldListSpacer)
                } else {
                    listOf(BeautifyHome.fieldRecyclerActionBar, BeautifyHome.fieldRecyclerSpacer)
                }
            fields.mapNotNull { viewField(it, nativeList) }.forEach(::add)
            nativeTitleContainer()?.let(::add)
        }
            .filterNot { view ->
                view.containsHostView(shell) ||
                    view.containsHostView(originalRoot) ||
                    view.containsHostView(nativeList)
            }
            .toSet()
        for (view in chrome) {
            if (homeVisible) {
                chromeSizes.getOrPut(view) { ChromeSize(view) }
                val params = view.layoutParams
                val margins = params as? ViewGroup.MarginLayoutParams
                if (
                    params.height != 0 ||
                        margins != null && (margins.topMargin != 0 || margins.bottomMargin != 0)
                ) {
                    params.height = 0
                    margins?.topMargin = 0
                    margins?.bottomMargin = 0
                    view.layoutParams = params
                }
            } else {
                chromeSizes.remove(view)?.restore(view)
            }
        }
        for (view in activity.window.decorView.allViews) {
            val title =
                view in chrome ||
                    view.id in chromeIds ||
                    view.hasHostClass("androidx.appcompat.widget.ActionBarContainer")
            val bottomTab = view.hasHostClass("com.tencent.mm.ui.LauncherUIBottomTabView")
            if (bottomTab && replacementOwnsBar) {
                // The replacement owns native/docked/floating containers and their style.
                hiddenViews.remove(view)
                continue
            }
            if (title || bottomTab) {
                if (homeVisible) {
                    hiddenViews.getOrPut(view) { view.visibility }
                    if (view.visibility != View.GONE) view.visibility = View.GONE
                } else {
                    val original = hiddenViews.remove(view)
                    if (bottomTab && launcherForeground) {
                        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
                    } else if (original != null) view.visibility = original
                }
            }
        }
        if (nativeList.paddingTop != 0 || nativeList.paddingBottom != 0) {
            nativeList.setPadding(nativeList.paddingLeft, 0, nativeList.paddingRight, 0)
        }
        (nativeList.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            if (it.topMargin != 0 || it.bottomMargin != 0) {
                it.topMargin = 0
                it.bottomMargin = 0
                nativeList.layoutParams = it
            }
        }
    }

    private fun nativeTitleContainer(): View? {
        val homeUI = BeautifyHome.homeUI(activity)
        val titleMethod = BeautifyHome.methodNativeTitleView.method
        val actionBar =
            homeUI.reflekt().firstField { type = titleMethod.declaringClass }.get() ?: return null
        var title = titleMethod.invoke(actionBar) as? View ?: return null
        val roots = listOf(shell, originalRoot, nativeList)
        if (roots.any { it.containsHostView(title) }) return null
        while (true) {
            val parent = title.parent as? View ?: break
            if (roots.any { it.containsHostView(parent) }) break
            title = parent
        }
        return title
    }

    private fun openSearch() {
        val homeUI = BeautifyHome.homeUI(activity)
        val item =
            homeUI.reflekt().getField(BeautifyHome.fieldSearchItem.field.name) as MenuItem?
                ?: error("Native search item has not been initialized")
        val clickTarget = item.actionView?.allViews?.firstOrNull { it.hasOnClickListeners() }
        check(clickTarget?.performClick() ?: activity.onOptionsItemSelected(item)) {
            "Native search action unavailable"
        }
    }

    private fun readMenu(): List<HomeMenuItem> =
        try {
            val menu = BeautifyHome.prepareMenu(activity)
            @Suppress("UNCHECKED_CAST")
            val map =
                menu.reflekt().firstField { type = SparseArray::class }.get()!! as SparseArray<Any>
            (0 until map.size)
                .mapNotNull { index ->
                    val key = map.keyAt(index)
                    val wrapper = map.valueAt(index) ?: return@mapNotNull null
                    val data =
                        wrapper
                            .reflekt()
                            .firstField {
                                modifiers { Modifier.STATIC !in it }
                                type { !it.isPrimitive && !it.name.startsWith("java.") }
                            }
                            .get()!!
                    val title =
                        data
                            .reflekt()
                            .firstField {
                                type = String::class
                                modifiers { Modifier.STATIC !in it }
                            }
                            .get()!!
                            as String
                    HomeMenuItem(key, title) {
                        action {
                            val current =
                                menu.reflekt().firstField { type = SparseArray::class }.get()
                            check(current === map && map[key] === wrapper) {
                                "Native menu changed; open it again"
                            }
                            menu
                                .reflekt()
                                .firstMethod {
                                    name = "onItemClick"
                                    parameters(
                                        AdapterView::class,
                                        View::class,
                                        Int::class,
                                        Long::class,
                                    )
                                }
                                .invoke(null, shell, key, 0L)
                        }
                    }
                }
                .distinctBy { it.title }
        } catch (error: Exception) {
            reportActionError(error)
            emptyList()
        }

    private fun action(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            reportActionError(error)
        }
    }

    private fun reportActionError(error: Exception) {
        WeLogger.e("BeautifyHome", "Native home action failed", error)
        showToast(activity, localizedString(R.string.beautify_home_action_failed))
    }

    fun captureStatusBarHeight(view: View, height: Int): Boolean =
        statusBar.captureHeight(view, height)

    fun restoreStatusBar() {
        try {
            statusBar.restore()
        } catch (error: Exception) {
            WeLogger.e("BeautifyHome", "Unable to restore native status bar", error)
        }
    }

    fun onResume() {
        resumed = true
        applyLayout()
    }

    fun onPause() {
        resumed = false
        restoreStatusBar()
    }

    fun onStop() {
        onPause()
    }

    fun onHomeLeaving() {
        homeVisible = false
        LauncherNavigation.setBarOverride(activity, null)
        LauncherNavigation.syncBar(activity)
        restoreStatusBar()
        chromeSizes.forEach { (view, size) -> size.restore(view) }
        chromeSizes.clear()
        hiddenViews.forEach { (view, visibility) -> view.visibility = visibility }
        hiddenViews.clear()
    }

    fun detach() {
        if (disposed) return
        disposed = true
        LauncherNavigation.setBarOverride(activity, null)
        LauncherNavigation.syncBar(activity)
        shell.removeCallbacks(watchdog)
        restoreStatusBar()
        activity.window.decorView.viewTreeObserver.let {
            if (it.isAlive) it.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        (nativeList.parent as? ViewGroup)?.removeView(nativeList)
        shell.disposeComposition()
        (shell.parent as? ViewGroup)?.removeView(shell)
        nativeList.isSaveFromParentEnabled = originalListSaveEnabled
        nativeList.setPadding(
            originalPadding[0],
            originalPadding[1],
            originalPadding[2],
            originalPadding[3],
        )
        chromeSizes.forEach { (view, size) -> size.restore(view) }
        chromeSizes.clear()
        originalListParent.addView(
            nativeList,
            originalListIndex.coerceIn(0, originalListParent.childCount),
            originalListParams,
        )
        originalRoot.visibility = originalRootVisibility
        hiddenViews.forEach { (view, visibility) -> view.visibility = visibility }
        hiddenViews.clear()
    }

    private fun viewField(delegate: DexFieldDelegate, instance: Any): View? =
        instance.reflekt().getField(delegate.field.name) as View?

    private class ChromeSize(view: View) {
        private val height = view.layoutParams.height
        private val margins =
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                intArrayOf(it.topMargin, it.bottomMargin)
            }

        fun restore(view: View) {
            val params = view.layoutParams
            params.height = height
            if (margins != null && params is ViewGroup.MarginLayoutParams) {
                params.topMargin = margins[0]
                params.bottomMargin = margins[1]
            }
            view.layoutParams = params
        }
    }
}

private fun View.hasHostClass(name: String): Boolean =
    generateSequence(javaClass as Class<*>?) { it.superclass }.any { it.name == name }

private fun View.containsHostView(child: View): Boolean =
    generateSequence(child) { it.parent as? View }.any { it === this }
