package dev.ujhhgtg.wekit.ui.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import java.util.WeakHashMap

object LifecycleOwnerProvider {
    // Non-Activity overlays still use this owner; Activity-backed views use getOrCreate.
    val lifecycleOwner by lazy { XposedLifecycleOwner.create() }
    private val owners = WeakHashMap<Activity, XposedLifecycleOwner>()
    private val states = WeakHashMap<Activity, Lifecycle.State>()
    private var installed = false

    /** One application callback shared by all injected Activity compositions. */
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                update(activity, Lifecycle.State.CREATED)
            override fun onActivityStarted(activity: Activity) = update(activity, Lifecycle.State.STARTED)
            override fun onActivityResumed(activity: Activity) = update(activity, Lifecycle.State.RESUMED)
            override fun onActivityPaused(activity: Activity) = update(activity, Lifecycle.State.STARTED)
            override fun onActivityStopped(activity: Activity) = update(activity, Lifecycle.State.CREATED)
            override fun onActivityDestroyed(activity: Activity) {
                owners.remove(activity)?.onDestroy()
                states.remove(activity)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    fun getOrCreate(activity: Activity): XposedLifecycleOwner {
        install(activity.application)
        return owners.getOrPut(activity) {
            // MainTabUI can create views inside onCreate, before onActivityCreated
            XposedLifecycleOwner.create(states[activity] ?: Lifecycle.State.CREATED)
        }
    }

    private fun update(activity: Activity, state: Lifecycle.State) {
        states[activity] = state
        owners[activity]?.moveTo(state)
    }
}
