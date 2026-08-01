package dev.ujhhgtg.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.View
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ujhhgtg.wekit.features.api.ui.WeHostDensity
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import kotlin.math.abs

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val context = CommonContextWrapper(context)
    val dialogContext = matchHostDensity(context) ?: context

    val dialog = ComponentDialog(
        dialogContext,
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

        setContentView(
            ComposeView(dialogContext).apply {
                setContent {
                    ModuleTheme {
                        Box(
                            modifier = Modifier.wrapContentSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            scope.content()
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        show()
    }
}

/**
 * WeChat's own activities render with MMDensityManager-adapted display metrics, while module
 * activities hosted in WeChat's process (SettingsActivity etc.) are created with plain framework
 * resources. Dialogs opened from those activities therefore render at the system density and look
 * bigger than the same dialogs opened inside WeChat. Wrap the dialog context with WeChat's
 * adapted metrics so Compose initializes at exactly the same scale as WeChat's own UI. Returns
 * null when there is no difference (e.g. inside WeChat activities), leaving the dialog unchanged.
 */
private fun matchHostDensity(context: Context): Context? = runCatching {
    val ownMetrics = context.resources.displayMetrics

    val hostMetrics = WeHostDensity.targetDisplayMetrics()
        ?: context.applicationContext.resources.displayMetrics
    if (hostMetrics.density <= 0f || hostMetrics.scaledDensity <= 0f) return@runCatching null
    if (abs(hostMetrics.density - ownMetrics.density) < 0.01f &&
        abs(hostMetrics.scaledDensity - ownMetrics.scaledDensity) < 0.01f
    ) {
        null
    } else {
        val baseResources = context.resources
        object : ContextThemeWrapper(context, context.theme) {
            private val hostResources = Resources(
                baseResources.assets,
                DisplayMetrics().apply { setTo(hostMetrics) },
                baseResources.configuration
            ).also { ResourcesInjector.injectModuleRes(it) }

            override fun getResources(): Resources = hostResources
        }
    }
}.getOrNull()

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}
