package moe.ouom.wekit.ui.compose

import android.app.Dialog
import android.content.Context
import android.graphics.Color
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
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.ui.theme.AppTheme

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun showComposeDialog(context: Context? = null, content: @Composable (onDismiss: () -> Unit) -> Unit) {
    var ctx = context

    ctx = if (ctx == null)
        HostInfo.getApplication()
    else
        CommonContextWrapper.createAppCompatContext(ctx)

    val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth)
    val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }

//    val displayMetrics = ctx.resources.displayMetrics
//    val screenHeight = displayMetrics.heightPixels

    dialog.apply {
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.requestFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setCancelable(false)

        setContentView(
            ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
//                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenHeight)

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
//        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, screenHeight)
    }
}
