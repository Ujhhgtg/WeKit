package dev.ujhhgtg.wekit.features.items.beautify.home

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_HOME
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.postOnUiThread
import org.luckypray.dexkit.DexKitBridge
import java.util.IdentityHashMap

object BeautifyHome : SwitchFeature(), IResolveDex {

    override val technicalId = "美化首页"
    override val nameRes = R.string.feature_beautify_home_name
    override val descriptionRes = R.string.feature_beautify_home_description
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)

    private val methodConversationReady by dexMethod("""SELECT DISTINCT ms.method AS method FROM method_strings ms JOIN methods m ON m.descriptor = ms.method WHERE ms.value IN ('MicroMsg.MainUI', 'main ui init view') AND m.class_descriptor = 'Lcom/tencent/mm/ui/conversation/MainUI;' AND m.return_descriptor = 'V' GROUP BY ms.method HAVING COUNT(DISTINCT ms.value) = 2 ORDER BY ms.method;""")
    private val methodListMargins by dexMethod("""SELECT DISTINCT ms.method AS method FROM method_strings ms JOIN methods m ON m.descriptor = ms.method WHERE ms.value IN ('MicroMsg.MainUI', 'setListMargin isInMultiWindowMode: %b') AND m.class_descriptor = 'Lcom/tencent/mm/ui/conversation/MainUI;' AND m.return_descriptor = 'V' GROUP BY ms.method HAVING COUNT(DISTINCT ms.value) = 2 ORDER BY ms.method;""")
    val methodNativeTitleView by dexMethod("""SELECT DISTINCT ms.method AS method FROM method_strings ms JOIN methods m ON m.descriptor = ms.method WHERE ms.value IN ('[initActionBar] mActionBar == null') AND m.class_descriptor LIKE 'Landroidx/appcompat/app/%' AND m.class_descriptor = 'Lcom/tencent/mm/ui/HomeUI;' AND m.return_descriptor = 'Landroid/view/View;' AND m.param_count = 0 GROUP BY ms.method HAVING COUNT(DISTINCT ms.value) = 1 ORDER BY ms.method;""")
    val methodStatusBarHeight by dexMethod("""SELECT DISTINCT ms.method AS method FROM method_strings ms JOIN methods m ON m.descriptor = ms.method WHERE ms.value IN ('MicroMsg.DrawStatusBarFrameLayout', 'applyStatusBarHeight setPadding e=%s') AND m.name = 'getDrawnStatusBarHeight' AND m.return_descriptor = 'I' AND m.param_count = 0 GROUP BY ms.method HAVING COUNT(DISTINCT ms.value) = 2 ORDER BY ms.method;""")
    val fieldListActionBar by dexField {
        matcher {
            declaredClass = "com.tencent.mm.ui.conversation.ConversationListView"
            type = "android.view.View"
            addReadMethod { usingEqStrings("resetActionBarView") }
        }
    }
    val fieldListSpacer by dexField()
    val fieldMainStatusMask by dexField {
        matcher {
            declaredClass = "com.tencent.mm.ui.conversation.MainUI"
            type = "android.view.View"
            addReadMethod { usingEqStrings("setListMargin isInMultiWindowMode: %b") }
        }
    }

    val classRecyclerView by dexClass("""SELECT descriptor FROM classes WHERE descriptor = 'Lcom/tencent/mm/ui/conversation/recycler/ConversationRecyclerView;' ORDER BY descriptor;""", allowFailure = true)
    val fieldRecyclerActionBar by dexField()
    val fieldRecyclerSpacer by dexField()

    private val fieldPlusItem by dexField {
        matcher {
            declaredClass = "com.tencent.mm.ui.HomeUI"
            type = "android.view.MenuItem"
            addReadMethod { usingEqStrings("bindPlusItemActionView plusActionView") }
        }
    }
    val fieldSearchItem by dexField()
    val methodShowPlusMenu by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        fieldListSpacer.setDescriptor(dexKit.findField {
            matcher {
                declaredClass = "com.tencent.mm.ui.conversation.ConversationListView"
                type = "android.view.View"
                addWriteMethod { usingEqStrings("initActionBarView check multiWindowMode %b") }
            }
        }.single { it.descriptor != fieldListActionBar.data.descriptor })

        if (classRecyclerView.isFound) {
            fieldRecyclerActionBar.find(dexKit) {
                matcher {
                    declaredClass(classRecyclerView.data.name)
                    type = "android.view.View"
                    addReadMethod { usingEqStrings($$"resetActionBarView$app_release") }
                }
            }
            fieldRecyclerSpacer.setDescriptor(dexKit.findField {
                matcher {
                    declaredClass(classRecyclerView.data.name)
                    type = "android.view.View"
                    addWriteMethod {
                        usingEqStrings("[setupActionBarView] statusBarHeight changed: %d -> %d (abvTop=%d isMW=%b)")
                    }
                }
            }.single { it.descriptor != fieldRecyclerActionBar.data.descriptor })
        } else {
            fieldRecyclerActionBar.markExpectedFailure()
            fieldRecyclerSpacer.markExpectedFailure()
        }

        // Across both layouts HomeUI has a search and a plus MenuItem. Identify the plus item
        // by its binding method, rather than relying on the newer contacts-search alpha log.
        fieldSearchItem.setDescriptor(dexKit.findField {
            matcher {
                declaredClass = "com.tencent.mm.ui.HomeUI"
                type = "android.view.MenuItem"
            }
        }.single { it.descriptor != fieldPlusItem.data.descriptor })

        val buildMenu = WeHomeScreenPopupMenuApi.methodAddItem.data
        val plusMenuClass = WeHomeScreenPopupMenuApi.methodHandleItemClick.data.declaredClass!!
        methodShowPlusMenu.find(dexKit) {
            matcher {
                declaredClass(plusMenuClass.superClass!!.name)
                returnType = "boolean"
                // Older menu classes build data in their zero-argument show override; newer
                // HomeUI prepares it before calling the superclass show method with an offset.
                if (buildMenu.declaredClassName == "com.tencent.mm.ui.HomeUI") paramTypes("int")
                else paramCount = 0
                addCaller(buildMenu.descriptor)
            }
        }
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val sessions = IdentityHashMap<Any, HomeSession>()
    private val pendingInstall = IdentityHashMap<Any, Runnable>()
    private var suppressedMenu: Any? = null

    override fun onEnable() {
        HomeWallpaper.initialize()
        methodConversationReady.hookAfter { scheduleInstall(thisObject!!) }
        methodConversationReady.method.declaringClass.reflekt().firstMethod {
            superclass()
            name = "onDestroyView"
            parameters()
        }.hookBefore { detach(thisObject!!) }
        methodListMargins.hookBefore {
            if (sessions.containsKey(thisObject)) {
                args[0] = 0
                args[1] = 0
            }
        }
        methodStatusBarHeight.hookBefore {
            val view = thisObject as View
            sessions.values.forEach { session ->
                if (session.captureStatusBarHeight(view, args[0] as Int)) args[0] = 0
            }
        }
        methodShowPlusMenu.hookBefore {
            if (thisObject === suppressedMenu) result = false
        }
        WeMainActivityBeautifyApi.addListener(this, object : WeMainActivityBeautifyApi.Listener {
            override fun onTabsCreated(home: WeMainActivityBeautifyApi.Home) {
                refreshNavigation(home)
                home.fragment(TAB_HOME)?.let(::scheduleInstall)
            }
            override fun onBeforePageSelected(home: WeMainActivityBeautifyApi.Home, tab: Int) {
                if (tab != TAB_HOME) forActivity(home.activity) { it.restoreStatusBar() }
            }
            override fun onPageSelected(home: WeMainActivityBeautifyApi.Home, tab: Int) {
                forActivity(home.activity) {
                    it.conversationSelected = tab == TAB_HOME
                    it.applyLayout()
                }
            }
            override fun onTabsChanged(home: WeMainActivityBeautifyApi.Home) = refreshNavigation(home)
            override fun onBeforeChatting(activity: Activity) = forActivity(activity) { it.onHomeLeaving() }
            override fun onChatTransition(activity: Activity) = forActivity(activity) { it.applyLayout() }
            override fun onResume(activity: Activity) {
                HomeWallpaper.refreshSystemWallpaper()
                forActivity(activity) { it.onResume() }
            }
            override fun onPause(activity: Activity) = forActivity(activity) { it.onPause() }
            override fun onStop(activity: Activity) = forActivity(activity) { it.onStop() }
            override fun onDestroy(activity: Activity) {
                sessions.entries.filter { it.value.activity === activity }.map { it.key }.forEach(::detach)
            }
        })
    }

    private fun refreshNavigation(home: WeMainActivityBeautifyApi.Home) {
        forActivity(home.activity) {
            it.navigationTabs = home.tabs.filter { tab -> tab != TAB_HOME }
            it.conversationSelected = home.selectedTab == TAB_HOME
            it.applyLayout()
        }
    }

    private fun forActivity(activity: Activity, action: (HomeSession) -> Unit) {
        sessions.values.filter { it.activity === activity }.forEach(action)
    }

    override fun onDisable() {
        handler.removeCallbacksAndMessages(null)
        pendingInstall.clear()
        WeMainActivityBeautifyApi.removeListener(this)
        postOnUiThread {
            sessions.values.toList().forEach { it.detach() }
            sessions.clear()
            suppressedMenu = null
        }
    }

    fun navigate(activity: Activity, page: Int) {
        require(page in 0..3)
        WeMainActivityBeautifyApi.homeFor(activity)!!.navigate(page)
    }

    fun prepareMenu(activity: Activity): Any {
        val homeUI = homeUI(activity)
        val build = WeHomeScreenPopupMenuApi.methodAddItem.method
        val menuClass = WeHomeScreenPopupMenuApi.methodHandleItemClick.method.declaringClass
        val menu = homeUI.reflekt().firstField { type = menuClass }.get()!!
        suppressedMenu = menu
        try {
            build.invoke(if (build.declaringClass.isInstance(homeUI)) homeUI else menu)
        } finally {
            suppressedMenu = null
        }
        return menu
    }

    fun homeUI(activity: Activity): Any = activity.reflekt().firstField {
        type = "com.tencent.mm.ui.HomeUI"
    }.get()!!

    fun detach(mainUI: Any) {
        pendingInstall.remove(mainUI)?.let(handler::removeCallbacks)
        sessions.remove(mainUI)?.detach()
    }

    private fun scheduleInstall(mainUI: Any) {
        pendingInstall.remove(mainUI)?.let(handler::removeCallbacks)
        val install = Runnable {
            pendingInstall.remove(mainUI)
            if (!isActive || sessions.containsKey(mainUI)) return@Runnable
            val activity = mainUI.reflekt().firstMethod {
                superclass()
                name = "getContext"
                parameters()
            }.invoke() as? Activity ?: return@Runnable
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            val root = mainUI.reflekt().firstMethod {
                superclass()
                name = "getView"
                parameters()
            }.invoke() as? ViewGroup ?: return@Runnable
            val mainView = root.allViews.first {
                it.javaClass.name == "com.tencent.mm.ui.conversation.MainUIView"
            } as ViewGroup
            val session = HomeSession(mainUI, activity, mainView, mainView.parent as FrameLayout)
            val home = WeMainActivityBeautifyApi.homeFor(activity)!!
            session.navigationTabs = home.tabs.filter { it != TAB_HOME }
            session.conversationSelected = home.selectedTab == TAB_HOME
            try {
                session.attach()
                sessions[mainUI] = session
            } catch (error: Exception) {
                session.detach()
                WeLogger.e("BeautifyHome", "Unable to install conversation home", error)
            }
        }
        pendingInstall[mainUI] = install
        handler.post(install)
    }

}
