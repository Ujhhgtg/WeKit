package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.children
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

object BeautifyNavigationBar : ClickableFeature(), IResolveDex {

    override val technicalId = "美化首页底部导航栏"
    override val nameRes = R.string.feature_beautify_navigation_bar_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_navigation_bar_description

    private const val TAG = "BeautifyNavigationBar"

    private const val POSITION_NONE = -2

    private val mutableConfigState by lazy { mutableStateOf(NavBarPrefs.load()) }
    val configState: State<NavBarConfig> get() = mutableConfigState
    val config: NavBarConfig get() = mutableConfigState.value

    /**
     * Logical tabs currently backing the pager positions. Follows [NavBarConfig.visibleTabs],
     * but only changes together with the adapter notification in [applyPageSet].
     */
    var pagerTabs: List<Int> = emptyList()
        private set

    val unreadCountState = mutableIntStateOf(0)
    val finderUnreadCountState = mutableIntStateOf(0)
    val showFinderDotState = mutableStateOf(false)
    val contactUnreadCountState = mutableIntStateOf(0)

    // Always follow the host's Recent page, regardless of the scroll auto-hide setting.
    val recentPageHiddenState = mutableStateOf(false)

    // True while the bar should be hidden by the conversation-list scroll auto-hide.
    val barScrollHiddenState = mutableStateOf(false)

    private val sessions = mutableListOf<NavBarSession>()
    private val focusAlphaStates = WeakHashMap<View, MutableIntState>()

    // MainTabUIs whose doOnCreate (which constructs the TabsAdapter) ran while this feature
    // was enabled. A pager that already existed when the feature was toggled on keeps
    // WeChat's identity mapping: changing its count without a notifyDataSetChanged would
    // crash it.
    private val managedMainTabUIs: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val managedAdapters = WeakHashMap<Any, Boolean>()
    private val IDENTITY_TABS = NAV_TABS.map(NavTab::index)

    // Set by onClick until LauncherUI resumes and can host the editor.
    private var pendingEditor = false

    private val remapProgrammaticTab = ThreadLocal.withInitial { false }
    private val animateNextPageChange = ThreadLocal.withInitial { false }
    private val allowLogicalTabCount = ThreadLocal.withInitial { false }
    private val callbackPagerIndex = ThreadLocal<Int?>()
    private var pageSetChanging = false

    private fun liveSessions(): List<NavBarSession> {
        sessions.removeAll { it.activity.isDestroyed }
        return sessions.toList()
    }

    private fun isManaged(tabsAdapter: Any): Boolean = managedAdapters.getOrPut(tabsAdapter) {
        tabsAdapter.reflekt().firstField { type = "com.tencent.mm.ui.MainTabUI" }.get() in managedMainTabUIs
    }

    private fun tabsOf(tabsAdapter: Any?): List<Int> =
        if (isManaged(tabsAdapter!!)) pagerTabs else IDENTITY_TABS

    private fun sessionFor(tabsAdapter: Any?): NavBarSession? =
        liveSessions().firstOrNull { it.tabsAdapter === tabsAdapter }

    private fun sessionForActivity(activity: Activity): NavBarSession? =
        liveSessions().firstOrNull { it.activity === activity }

    fun registerFocusAlpha(icon: View, state: MutableIntState) {
        focusAlphaStates[icon] = state
    }

    fun updateConfig(transform: NavBarConfig.() -> NavBarConfig) {
        val old = config
        val new = old.transform().normalized()
        if (new == old) return
        NavBarPrefs.save(new)
        // The pager switches away from a page being hidden using the old mapping, so it
        // must move before the bars recompose against the new configuration.
        if (new.visibleTabs != pagerTabs) applyPageSet(new.visibleTabs)
        if (new.style != old.style || new.autoHide != old.autoHide) resetScrollTracking()
        mutableConfigState.value = new
        liveSessions().forEach { it.applyConfig(old, new) }
    }

    private fun applyPageSet(tabs: List<Int>) {
        val sessions = liveSessions()
        sessions.forEach { it.switchAwayFrom(pagerTabs, tabs) }
        pagerTabs = tabs
        pageSetChanging = true
        try {
            sessions.forEach { it.syncPager() }
        } finally {
            pageSetChanging = false
        }
    }

    private fun managesMainTabUI(mainTabUI: Any): Boolean = mainTabUI in managedMainTabUIs

    fun changeTab(mainTabUI: Any, tab: Int) {
        methodChangeTab.method.invoke(mainTabUI, tab)
    }

    private fun resetScrollTracking() {
        barScrollHiddenState.value = false
        scrollUpdated = false
        scrollingManually = false
        dragSawDownward = false
    }

    override fun onEnable() {
        pagerTabs = config.visibleTabs
        resetScrollTracking()

        val tabsAdapterClass = $$"com.tencent.mm.ui.MainTabUI$TabsAdapter".toClass()
        tabsAdapterClass.reflekt().apply {
            firstMethod { name = "getCount" }.hookAfter(priority = 100) {
                result = if (allowLogicalTabCount.get() == true) NAV_TABS.size else tabsOf(thisObject).size
            }
            firstMethod {
                name = "getItem"
                parameters(int)
            }.hookBefore(priority = 100) {
                args[0] = tabsOf(thisObject)[args[0] as Int]
            }

            listOf("onPageScrolled", "onPageSelected").forEach { callbackName ->
                firstMethod { name = callbackName }.apply {
                    hookBefore(priority = 100) {
                        val pagerIndex = args[0] as Int
                        callbackPagerIndex.set(pagerIndex)
                        args[0] = tabsOf(thisObject)[pagerIndex]
                    }
                    hookAfter(priority = 100) {
                        callbackPagerIndex.remove()
                    }
                }
            }

            firstMethod {
                name = "onTabClick"
                parameters(int)
            }.apply {
                hookBefore(priority = 100) {
                    if (args[0] as Int !in tabsOf(thisObject)) {
                        result = null
                    } else {
                        remapProgrammaticTab.set(true)
                        animateNextPageChange.set(true)
                    }
                }
                hookAfter(priority = 100) {
                    remapProgrammaticTab.remove()
                    animateNextPageChange.remove()
                }
            }

            firstMethod { name = "onPageScrolled" }.hookBefore {
                val session = sessionFor(thisObject) ?: return@hookBefore
                val position = callbackPagerIndex.get() ?: pagerTabs.indexOf(args[0] as Int).coerceAtLeast(0)
                val offset = args[1] as Float
                session.selectedPageIndexState.intValue = position
                session.scrollOffsetState.floatValue = offset

                // Leaving the conversation page always restores the bar and invalidates
                // the scroll-direction tracker, so returning to the list starts fresh.
                if (pagerTabs[position] != TAB_HOME) {
                    recentPageHiddenState.value = false
                    barScrollHiddenState.value = false
                    scrollUpdated = false
                }
            }
            firstMethod { name = "onPageScrolled" }.hookAfter {
                // WeChat pairs each tab with its logical neighbour; after reordering or
                // hiding pages the highlight has to follow pager neighbours instead.
                val session = sessionFor(thisObject) ?: return@hookAfter
                session.syncNativeFocus(pagerTabs.indexOf(args[0] as Int), args[1] as Float)
            }

            firstMethod { name = "onPageSelected" }.hookBefore {
                val session = sessionFor(thisObject) ?: return@hookBefore
                session.targetPageIndexState.intValue =
                    callbackPagerIndex.get() ?: pagerTabs.indexOf(args[0] as Int).coerceAtLeast(0)
            }

            // Live page set: map fragments to their new positions and keep hidden ones
            // alive (see NavBarSession.syncPager) instead of letting the adapter remove them.
            firstMethod {
                name = "getItemPosition"
                superclass()
            }.hookBefore {
                if (!pageSetChanging || thisObject!!.javaClass != tabsAdapterClass) return@hookBefore
                val session = sessionFor(thisObject)!!
                val tab = session.fragmentTab(args[0]!!)
                val position = if (tab == null) -1 else pagerTabs.indexOf(tab)
                result = if (position >= 0) position else POSITION_NONE
            }
            firstMethod {
                name = "destroyItem"
                parameters(ViewGroup::class, int, Any::class)
                superclass()
            }.hookBefore {
                if (!pageSetChanging || thisObject!!.javaClass != tabsAdapterClass) return@hookBefore
                sessionFor(thisObject)!!.fragmentView(args[2]!!)?.visibility = View.GONE
                result = null
            }
        }

        methodChangeTab.apply {
            hookBefore(priority = 100) {
                if (!managesMainTabUI(thisObject!!)) return@hookBefore
                val requestedIndex = args[0] as Int
                if (requestedIndex !in pagerTabs) {
                    args[0] = pagerTabs.first()
                }
                remapProgrammaticTab.set(true)
                // MainTabUI checks the logical WeChat index against getCount() before it
                // reaches the pager. Let that check see four logical tabs; the pager itself
                // sees the reduced count after setCurrentItem is entered below.
                allowLogicalTabCount.set(true)
            }
            hookAfter(priority = 100) {
                remapProgrammaticTab.remove()
                allowLogicalTabCount.remove()
                if (!managesMainTabUI(thisObject!!)) return@hookAfter

                val logicalIndex = args[0] as Int
                val pagerIndex = pagerTabs.indexOf(logicalIndex)
                if (pagerIndex >= 0) {
                    val viewPager = thisObject!!.reflekt()
                        .firstField { name = "mViewPager" }
                        .get()!! as WxViewPager
                    if (viewPager.currentItem != pagerIndex) {
                        viewPager.setCurrentItem(pagerIndex, false)
                    }
                }
            }
        }

        "com.tencent.mm.ui.mogic.WxViewPager".toClass().reflekt().apply {
            listOf("setCurrentItem", "setCurrentItemNotify").forEach { methodName ->
                firstMethod {
                    name = methodName
                    parameters(int, bool)
                }.hookBefore(priority = 100) {
                    if (remapProgrammaticTab.get() != true) return@hookBefore
                    val logicalIndex = args[0] as Int
                    val pagerIndex = pagerTabs.indexOf(logicalIndex)
                    if (pagerIndex >= 0) args[0] = pagerIndex
                    allowLogicalTabCount.set(false)
                    // The second parameter is the pager's `smoothScroll` flag. Flipping it to
                    // true makes WxViewPager animate the same horizontal slide a finger swipe
                    // produces. This is scoped to `onTabClick`-originated changes (actual tab
                    // taps) only: MainTabUI.a(int) is also driven by programmatic flows that
                    // fire rapid same-frame tab bounces — e.g. returning from the wallet
                    // "服务" page starts LauncherUI with FLAG_ACTIVITY_CLEAR_TOP +
                    // preferred_tab, which makes MainTabUI.f() call a(0) then a(3) back to
                    // back. Stock WeChat snaps both (smoothScroll=false) so the bounce is
                    // invisible; animating both round-trips desyncs the pager (content stays
                    // on the first page while the logical tab says the second). The
                    // state-restore and first-layout paths never reach here either because
                    // the `remapProgrammaticTab` guard is only armed by tab interactions.
                    // Non-adjacent jumps sweep past the pages in between, but MainTabUI sets
                    // an offscreen page limit of 4, so every one of them is alive and renders
                    // real content. The pager caps the scroll duration at 600ms on its own.
                    if (config.animatePageChange && animateNextPageChange.get() == true) args[1] = true
                }
            }
        }

        // Before the TabsAdapter constructor calls setAdapter(), which already reads getCount().
        WeMainActivityBeautifyApi.methodDoOnCreate.hookBefore {
            managedMainTabUIs += thisObject!!
        }

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            recentPageHiddenState.value = false
            val mainTabUI = thisObject!!
            val activity = mainTabUI.reflekt()
                .firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                .get()!! as Activity
            val viewPager = mainTabUI.reflekt()
                .firstField { name = "mViewPager" }
                .get()!! as WxViewPager
            val tabsAdapter = mainTabUI.reflekt()
                .firstField { name = "mTabsAdapter" }
                .get()!!
            if (!isManaged(tabsAdapter)) return@hookAfter
            val bottomTab = (viewPager.parent as ViewGroup).children
                .first { it.javaClass.name == "com.tencent.mm.ui.LauncherUIBottomTabView" } as ViewGroup

            val session = NavBarSession(activity, mainTabUI, viewPager, tabsAdapter, bottomTab)
            sessions += session

            // Counters posted before this bar existed are kept by the native view.
            bottomTab.reflekt().apply {
                unreadCountState.intValue = firstMethod { name = "getMainTabUnread" }.invoke() as Int
                contactUnreadCountState.intValue = firstMethod { name = "getContactTabUnread" }.invoke() as Int
                finderUnreadCountState.intValue = firstMethod { name = "getFriendTabUnread" }.invoke() as Int
                showFinderDotState.value = firstMethod { name = "getShowFriendPoint" }.invoke() as Boolean
            }
            session.applyConfig(null, config)
        }

        "com.tencent.mm.ui.TabIconView".toClass().firstMethod {
            name = "setFocusAlpha"
            parameters(int)
        }.hookAfter {
            focusAlphaStates[thisObject as View]?.intValue = args[0] as Int
        }

        LauncherUI::class.reflekt().firstMethod {
            name = "moveTaskToBack"
            parameters(Boolean::class)
        }.hookBefore {
            val editor = sessionForActivity(thisObject as Activity)?.editor ?: return@hookBefore
            editor.onBack()
            result = true
        }

        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter {
            if (!pendingEditor) return@hookAfter
            pendingEditor = false
            val session = sessionForActivity(thisObject as Activity)
            if (session == null) {
                // Enabled after this LauncherUI was created: the bar hooks never saw it.
                showToast(HostInfo.application.getString(R.string.restart_wechat_to_apply))
                return@hookAfter
            }
            session.openEditor()
        }

        // TaskBarAnimController starts this listener only when it actually hides the
        // native tabs. Its generic isOpen callback also fires while closing/cancelling.
        // The native bar already follows the host's own animation.
        methodRecentPageHideAnimationStart.hookAfter {
            recentPageHiddenState.value = true
        }
        methodRecentPageClose.hookAfter {
            recentPageHiddenState.value = false
        }

        // The native bar keeps rendering its own badges (hidden per tab through alpha);
        // mirror the counters for the custom bar.
        methodUpdateTabUnread.hookAfter {
            unreadCountState.intValue = args[0] as Int
        }
        methodUpdateFriendTabUnread.hookAfter {
            finderUnreadCountState.intValue = args[0] as Int
        }
        methodShowFriendPoint.hookAfter {
            showFinderDotState.value = args[0] as Boolean
        }
        // The count of unhandled friend requests (fmessage_conversation.isNew).
        methodUpdateContactTabUnread.hookAfter {
            contactUnreadCountState.intValue = args[0] as Int
        }

        // Scroll auto-hide: observe the home conversation list. ConversationListView extends
        // ListView and installs itself as its own OnScrollListener, so instead of replacing
        // that listener (which would break WeChat's own handling) we hook its listener
        // methods directly. They fire on every item-scroll position change, drag or fling.
        val conversationListViewClass = "com.tencent.mm.ui.conversation.ConversationListView".toClass()
        conversationListViewClass.reflekt().apply {
            firstMethod {
                name = "onScroll"
                parameters { it.size == 4 }
            }.hookAfter {
                // Same gating as NagramXF: hide on any downward movement, but only show
                // again while the finger is actively dragging the list up (touch scroll);
                // a settling downward fling must not resurrect the bar.
                if (!config.style.isFloating || !config.autoHide) return@hookAfter
                val list = thisObject as AbsListView
                val firstPosition = args[1] as Int
                val firstViewTop = list.getChildAt(0)?.top ?: return@hookAfter
                updateConversationScroll(firstPosition, firstViewTop)
            }

            firstMethod {
                name = "onScrollStateChanged"
                parameters { it.size == 2 }
            }.hookAfter {
                scrollingManually =
                    args[1] as Int == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL
                if (scrollingManually) dragSawDownward = false
            }
        }

        val recyclerOnScrolled = WeConversationListViewApi.methodRecyclerOnScrolled
        if (!recyclerOnScrolled.isPlaceholder) {
            recyclerOnScrolled.hookAfter {
                if (!config.style.isFloating || !config.autoHide) return@hookAfter
                val recyclerView = args[0] as ViewGroup
                val firstPosition =
                    WeConversationListViewApi.methodRecyclerFirstVisiblePosition.method
                        .invoke(recyclerView) as Int
                if (firstPosition < 0) return@hookAfter
                val firstViewTop = recyclerView.getChildAt(0)?.top ?: return@hookAfter
                updateConversationScroll(firstPosition, firstViewTop)
            }
            WeConversationListViewApi.methodRecyclerOnScrollStateChanged.hookAfter {
                scrollingManually = args[1] as Int == 1
                if (scrollingManually) dragSawDownward = false
            }
        }

        // Only the native bar keeps WeChat's bottom frost. WeKit's bars draw their own
        // background and the hidden style has none, so suppress the host overlay there,
        // including before doOnCreate finishes, so no frosted strip is left behind.
        "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
            parameters { it[0] == bool && it[1] == int }
        }.hookBefore {
            if (config.style != NavBarStyle.NATIVE) args[0] = false
        }
    }

    override fun onDisable() {
        super.onDisable()
        pendingEditor = false
        liveSessions().forEach { it.editor?.dismiss() }
    }

    // Conversation-list scroll tracking, mirroring NagramXF's DialogsActivity logic:
    // direction is derived from the first visible item's position/top delta, and the
    // bar only reappears while the finger is actively dragging up — so it never pops
    // back the moment a downward fling settles. All touched from the main thread only.
    private var scrollPrevPosition = -1
    private var scrollPrevTop = 0
    private var scrollUpdated = false
    private var scrollingManually = false

    // Whether the current touch drag has scrolled the list downward yet, reset on each new
    // drag. A downward fling that follows a downward drag is a genuine user scroll, while a
    // downward motion during a fling that never had one is WeChat's top overscroll bounce.
    private var dragSawDownward = false

    private fun updateConversationScroll(firstPosition: Int, firstViewTop: Int) {
        val goingDown: Boolean
        val changed: Boolean
        if (scrollPrevPosition == firstPosition) {
            val topDelta = scrollPrevTop - firstViewTop
            goingDown = firstViewTop < scrollPrevTop
            changed = abs(topDelta) > 1
        } else {
            goingDown = firstPosition > scrollPrevPosition
            changed = true
        }
        // Pull-down overscroll springs the first row upward after release. Suppress that
        // bounce unless the same gesture actually dragged the list downward first.
        if (scrollingManually && goingDown) dragSawDownward = true
        val isOverscrollBounceBack = goingDown && !scrollingManually &&
            !dragSawDownward && firstPosition == scrollPrevPosition
        if (changed && scrollUpdated && (goingDown || scrollingManually) &&
            !isOverscrollBounceBack
        ) {
            barScrollHiddenState.value = goingDown
        }
        scrollPrevPosition = firstPosition
        scrollPrevTop = firstViewTop
        scrollUpdated = true
    }

    /**
     * Returns to WeChat's home and edits the bar in place. [CLEAR_TOP][Intent.FLAG_ACTIVITY_CLEAR_TOP]
     * finishes everything above LauncherUI, wherever the settings were opened from.
     */
    override fun onClick(context: ComponentActivity) {
        if (!isEnabled) {
            showToast(context, context.getString(R.string.nav_edit_enable_first))
            return
        }
        pendingEditor = true
        runCatching {
            context.startActivity(Intent().apply {
                setClassName(HostInfo.packageName, "com.tencent.mm.ui.LauncherUI")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        }.onFailure {
            pendingEditor = false
            WeLogger.e(TAG, "无法返回微信主界面", it)
        }
        context.finish()
    }

    private val methodRecentPageHideAnimationStart by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings($$"com/tencent/mm/plugin/taskbar/ui/TaskBarAnimController$6")
            }
            name = "onAnimationStart"
            paramTypes("android.animation.Animator")
            returnType = "void"
        }
    }

    private val methodRecentPageClose by dexMethod {
        matcher {
            usingEqStrings("com/tencent/mm/plugin/taskbar/ui/TaskBarAnimController", "onClose")
            paramTypes("boolean")
            returnType = "void"
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
        }
    }

    private val methodChangeTab by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings(
                "change tab to %d, cur tab %d, has init tab %B, tab cache size %d"
            )
        }
    }

    private val methodUpdateFriendTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateFriendTabUnread] unread : ")
        }
    }

    private val methodShowFriendPoint by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[showFriendPoint] show : ")
        }
    }

    private val methodUpdateContactTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateContactTabUnread] unread : ")
        }
    }
}
