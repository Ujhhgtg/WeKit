package dev.ujhhgtg.wekit.activity

import android.app.Dialog
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
import dev.ujhhgtg.wekit.utils.android.isDarkMode

class TransparentActivity : FragmentActivity() {

    private var autoFinishAfterAction = false
    private var actionCompleted = false
    private var hostedDialogCount = 0

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

        val request = pendingRequest ?: run { finish(); return }
        pendingRequest = null
        autoFinishAfterAction = request.autoFinish
        request.action(this)
        actionCompleted = true
        maybeAutoFinish()
    }

    internal fun registerHostedDialog(dialog: Dialog) {
        if (!autoFinishAfterAction) return
        hostedDialogCount++
        dialog.setOnDismissListener {
            hostedDialogCount--
            maybeAutoFinish()
        }
    }

    private fun maybeAutoFinish() {
        if (!autoFinishAfterAction || !actionCompleted || hostedDialogCount != 0) return
        window.decorView.post {
            if (autoFinishAfterAction && actionCompleted && hostedDialogCount == 0 && !isFinishing) {
                finish()
            }
        }
    }

    companion object {
        private data class PendingRequest(
            val autoFinish: Boolean,
            val action: FragmentActivity.() -> Unit,
        )

        @Volatile
        private var pendingRequest: PendingRequest? = null

        fun launch(context: Context, action: FragmentActivity.() -> Unit) {
            launch(context, autoFinish = false, action = action)
        }

        fun launchAutoFinish(context: Context, action: FragmentActivity.() -> Unit) {
            launch(context, autoFinish = true, action = action)
        }

        private fun launch(
            context: Context,
            autoFinish: Boolean,
            action: FragmentActivity.() -> Unit,
        ) {
            pendingRequest = PendingRequest(autoFinish, action)
            context.startActivity(
                Intent(context, TransparentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(BuildConfig.TAG, true)
                }
            )
        }
    }
}
