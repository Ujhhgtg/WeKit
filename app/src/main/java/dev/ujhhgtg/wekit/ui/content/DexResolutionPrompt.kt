package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows the non-dismissable [DexResolver] dialog. */
object DexResolutionPrompt {
    private const val TAG = "DexResolutionPrompt"

    fun show(context: Context, items: List<BaseFeature>, mode: DexResolutionMode) {
        showComposeDialog(context, directlyDismissable = false) {
            DexResolver(items, mode, MainScope(), onDismiss)
        }
    }

    /** Waits up to 30 s for [LauncherUI] during startup, then shows the dialog on it. */
    fun showWhenLauncherReady(items: List<BaseFeature>, mode: DexResolutionMode) {
        WeLogger.i(TAG, "launching background coroutine to resolve ${items.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1.seconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for Dex resolution dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                show(boundActivity, items, mode)
            }
        }
    }
}
