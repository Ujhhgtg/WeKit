package dev.ujhhgtg.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
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
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme

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

    val dialog = ComponentDialog(
        context,
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
            ComposeView(context).apply {
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
