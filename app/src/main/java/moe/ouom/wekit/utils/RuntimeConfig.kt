package moe.ouom.wekit.utils

import android.app.Activity
import android.content.SharedPreferences
import java.lang.ref.WeakReference

object RuntimeConfig {

    private var launcherUiActivityRef: WeakReference<Activity>? = null
    private lateinit var mmPrefs: SharedPreferences

    fun getLauncherUiActivity(): Activity? {
        val activity = launcherUiActivityRef?.get()
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            launcherUiActivityRef = null
            return null
        }
        return activity
    }

    fun setLauncherUiActivity(activity: Activity?) {
        launcherUiActivityRef = activity?.let { WeakReference(it) }
    }

    fun setMmPrefs(sharedPreferences: SharedPreferences) {
        mmPrefs = sharedPreferences
    }

    fun getLoggedInWxId(): String =
        mmPrefs.getString("login_weixin_username", "") ?: ""
}