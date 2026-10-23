package dev.ujhhgtg.wekit.features.api.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.LauncherNavigation
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.XposedLifecycleOwner
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.IdentityHashMap

object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信主屏幕美化服务"
    override val nameRes = R.string.feature_we_main_activity_beautify_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_main_activity_beautify_api_description

    val methodDoOnCreate by dexMethod(
        sqlMethod {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        },
    )

    /** Native host shared by home decorations; tab IDs are logical WeChat IDs. */
    class Home(val activity: Activity, val mainTabUI: Any, val pager: WxViewPager, val adapter: Any) {
        val lifecycleOwner: XposedLifecycleOwner get() = LifecycleOwnerProvider.getOrCreate(activity)
        val tabs: List<Int> get() = LauncherNavigation.tabsOf(adapter)
        val selectedTab: Int get() = tabs[pager.currentItem]

        fun fragment(tab: Int): Any? {
            val position = tabs.indexOf(tab)
            if (position < 0) return null
            return adapter.reflekt().firstMethod { name = "getItem"; parameters(Int::class) }.invoke(position)
        }

        fun navigate(tab: Int) {
            check(tab in tabs) { "Home destination is no longer enabled" }
            adapter.reflekt().firstMethod { name = "onTabClick"; parameters(Int::class) }.invoke(tab)
        }
    }

    interface Listener {
        fun onBeforeTabsCreate(mainTabUI: Any) {}
        fun onTabsCreated(home: Home) {}
        fun onLauncherCreated(activity: Activity) {}
        fun onResume(activity: Activity) {}
        fun onPause(activity: Activity) {}
        fun onStop(activity: Activity) {}
        fun onDestroy(activity: Activity) {}
        fun onBeforeChatting(activity: Activity) {}
        fun onChatTransition(activity: Activity) {}
        fun onBeforePageSelected(home: Home, tab: Int) {}
        fun onPageSelected(home: Home, tab: Int) {}
        fun onPageScrolled(home: Home, tab: Int, offset: Float) {}
        fun onPageScrolledAfter(home: Home, tab: Int, offset: Float) {}
        fun onTabsChanged(home: Home) {}
    }

    private val listeners = linkedMapOf<BaseFeature, Listener>()
    private val homes = IdentityHashMap<Activity, Home>()
    private val adapterHomes = IdentityHashMap<Any, Home>()
    private val createdLaunchers = mutableSetOf<Activity>()
    private val hookedAdapters = mutableSetOf<Class<*>>()

    /** Features remove their registration in onDisable. Existing hosts can be replayed. */
    fun addListener(owner: BaseFeature, listener: Listener, replayExisting: Boolean = true) {
        listeners[owner] = listener
        if (!replayExisting) return
        createdLaunchers.toList().forEach { activity -> deliver(owner) { listener.onLauncherCreated(activity) } }
        homes.values.toList().forEach { home -> deliver(owner) { listener.onTabsCreated(home) } }
        createdLaunchers.toList().filter {
            LifecycleOwnerProvider.getOrCreate(it).lifecycle.currentState == Lifecycle.State.RESUMED
        }.forEach { activity -> deliver(owner) { listener.onResume(activity) } }
    }

    fun removeListener(owner: BaseFeature) { listeners.remove(owner) }
    fun homeFor(activity: Activity): Home? = homes[activity]

    override fun onEnable() {
        LifecycleOwnerProvider.install(HostInfo.application)
        methodDoOnCreate.hookBefore {
            val mainTabUI = thisObject!!
            dispatch { it.onBeforeTabsCreate(mainTabUI) }
        }
        methodDoOnCreate.hookAfter {
            val mainTabUI = thisObject!!
            val activity = mainTabUI.reflekt().firstField {
                type = "com.tencent.mm.ui.MMFragmentActivity"
            }.get()!! as Activity
            val home = Home(
                activity, mainTabUI,
                mainTabUI.reflekt().getField("mViewPager")!! as WxViewPager,
                mainTabUI.reflekt().getField("mTabsAdapter")!!,
            )
            homes.put(activity, home)?.let { adapterHomes.remove(it.adapter) }
            adapterHomes[home.adapter] = home
            hookAdapter(home)
            dispatch { it.onTabsCreated(home) }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, Bundle::class, Boolean::class)
        }.apply {
            hookBefore {
                val activity = thisObject as Activity
                dispatch { it.onBeforeChatting(activity) }
            }
            hookAfter {
                val activity = thisObject as Activity
                dispatch { it.onChatTransition(activity) }
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "closeChatting"
            parameters(Boolean::class)
        }.hookAfter {
            val activity = thisObject as Activity
            dispatch { it.onChatTransition(activity) }
        }
        LauncherNavigation.onTabsChanged = { adapter ->
            adapterHomes[adapter]?.let { home -> dispatch { it.onTabsChanged(home) } }
        }
        HostInfo.application.registerActivityLifecycleCallbacks(activityCallbacks)
    }

    private fun hookAdapter(home: Home) {
        if (!hookedAdapters.add(home.adapter.javaClass)) return
        home.adapter.reflekt().firstMethod { name = "onPageSelected"; parameters(Int::class) }.apply {
            // Replacement navigation maps args at priority 100 before these callbacks.
            hookBefore {
                adapterHomes[thisObject]?.let { current ->
                    val tab = args[0] as Int
                    dispatch { it.onBeforePageSelected(current, tab) }
                }
            }
            hookAfter {
                adapterHomes[thisObject]?.let { current ->
                    val tab = args[0] as Int
                    dispatch { it.onPageSelected(current, tab) }
                }
            }
        }
        home.adapter.reflekt().firstMethod {
            name = "onPageScrolled"
            parameters(Int::class, Float::class, Int::class)
        }.apply {
            hookBefore {
                adapterHomes[thisObject]?.let { current ->
                    val tab = args[0] as Int
                    val offset = args[1] as Float
                    dispatch { it.onPageScrolled(current, tab, offset) }
                }
            }
            hookAfter {
                adapterHomes[thisObject]?.let { current ->
                    val tab = args[0] as Int
                    val offset = args[1] as Float
                    dispatch { it.onPageScrolledAfter(current, tab, offset) }
                }
            }
        }
    }

    private fun dispatch(action: (Listener) -> Unit) {
        listeners.toList().forEach { (owner, listener) -> deliver(owner) { action(listener) } }
    }

    private fun deliver(owner: BaseFeature, action: () -> Unit) {
        if (!owner.isActive) return
        try { action() } catch (error: Exception) {
            WeLogger.e("WeMainActivityBeautifyApi", "Home callback failed for ${owner.technicalId}", error)
        }
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is LauncherUI) return
            createdLaunchers.add(activity)
            dispatch { it.onLauncherCreated(activity) }
        }
        override fun onActivityResumed(activity: Activity) {
            if (activity is LauncherUI) dispatch { it.onResume(activity) }
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity is LauncherUI) dispatch { it.onPause(activity) }
        }
        override fun onActivityStopped(activity: Activity) {
            if (activity is LauncherUI) dispatch { it.onStop(activity) }
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity !is LauncherUI) return
            dispatch { it.onDestroy(activity) }
            homes.remove(activity)?.let { adapterHomes.remove(it.adapter) }
            createdLaunchers.remove(activity)
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    override fun onDisable() {
        HostInfo.application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        LauncherNavigation.onTabsChanged = null
        listeners.clear()
        homes.clear()
        adapterHomes.clear()
        createdLaunchers.clear()
        hookedAdapters.clear()
    }
}
