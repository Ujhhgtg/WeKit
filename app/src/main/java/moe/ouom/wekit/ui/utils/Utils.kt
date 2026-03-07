package moe.ouom.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.ouom.wekit.host.HostInfo

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable;
// although technically you shouldn't use AlertDialog inside this function since both Dialog and AlertDialog create a new Window,
// there don't seem to be any problems if you insist on doing that
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun showComposeDialog(
    context: Context? = null,
    content: @Composable (onDismiss: () -> Unit) -> Unit
) {
    var ctx = context

    ctx = if (ctx == null)
        HostInfo.getApplication()
    else
        CommonContextWrapper.createAppCompatContext(ctx)

    val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth)
    val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }

    dialog.apply {
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.requestFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setCancelable(false)

        setContentView(
            ComposeView(ctx).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    AppTheme {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            content { dismiss() }
                        }
                    }
                }
            }
        )

        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setOnDismissListener { lifecycleOwner.onDestroy() }
        show()
    }
}

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    this.apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}