package dev.ujhhgtg.wekit.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.android.baseActivity
import dev.ujhhgtg.wekit.utils.android.isDarkMode

class TransparentActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            requestFeature(Window.FEATURE_NO_TITLE)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, this.decorView).isAppearanceLightStatusBars = !isDarkMode
        }
        setTheme(android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        val action = pendingAction ?: run { finish(); return }
        pendingAction = null
        action(this)
    }

    companion object {
        @Volatile
        private var pendingAction: (FragmentActivity.() -> Unit)? = null

        fun launch(context: Context, action: FragmentActivity.() -> Unit) {
            pendingAction = action
            // stack onto the caller's task when there is one: with NEW_TASK the host stub is placed
            // by its taskAffinity (WeChat's main task), which pulls LauncherUI to the front whenever
            // the caller lives in another task (e.g. a mini program's cashier)
            val activity = context.baseActivity
            (activity ?: context).startActivity(
                Intent(context, TransparentActivity::class.java).apply {
                    if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(BuildConfig.TAG, true)
                }
            )
        }
    }
}
