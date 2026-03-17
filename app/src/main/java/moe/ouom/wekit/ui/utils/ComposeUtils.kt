package moe.ouom.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.hooks.items.beautify.ApplyDialogBackgroundBlur
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.logging.WeLogger
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

private val TAG = nameof(::showComposeDialog)

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context? = null,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val ctx =
        if (context == null)
            HostInfo.application
        else
            CommonContextWrapper.create(context)

    val dialog = Dialog(
        ctx,
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )
    val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)

            if (!ApplyDialogBackgroundBlur.isEnabled) {
                return@apply
            }

            requestFeature(Window.FEATURE_NO_TITLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = WePrefs.getIntOrDef(
                    ApplyDialogBackgroundBlur.KEY_BLUR_RADIUS,
                    ApplyDialogBackgroundBlur.DEFAULT_BLUR_RADIUS
                )
            } else {
                WeLogger.w(TAG, "sdk < 31, not applying blur behind dialog")
            }
        }

        // FIXME: this doesn't work:
        // setCanceledOnTouchOutside(dismissable)
        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(ctx, this, window!!, ::dismiss)

        setContentView(
            ComposeView(ctx).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    AppTheme {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            scope.content()
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

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun <T> showModalComposeDialog(
    context: Context? = null,
    directlyDismissable: Boolean = false,
    content: @Composable ShowComposeDialogScope.(onResult: (T) -> Unit) -> Unit
): Result<T> {
    val resultFuture = CompletableFuture<T>()

    val showDialog = {
        val ctx =
            if (context == null)
                HostInfo.application
            else
                CommonContextWrapper.create(context)

        val dialog = Dialog(
            ctx,
            android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
        )
        val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }

        dialog.apply {
            window!!.apply {
                setBackgroundDrawableResource(android.R.color.transparent)

                if (ApplyDialogBackgroundBlur.isEnabled) {
                    requestFeature(Window.FEATURE_NO_TITLE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        attributes.blurBehindRadius = WePrefs.getIntOrDef(
                            ApplyDialogBackgroundBlur.KEY_BLUR_RADIUS,
                            ApplyDialogBackgroundBlur.DEFAULT_BLUR_RADIUS
                        )
                    } else {
                        WeLogger.w(TAG, "sdk < 31, not applying blur behind dialog")
                    }
                }
            }

            setCancelable(directlyDismissable)

            val scope = ShowComposeDialogScope(ctx, this, window!!, ::dismiss)

            val onResult: (T) -> Unit = { result ->
                resultFuture.complete(result)
                dismiss()
            }

            setContentView(
                ComposeView(ctx).apply {
                    setLifecycleOwner(lifecycleOwner)
                    setContent {
                        AppTheme {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                scope.content(onResult)
                            }
                        }
                    }
                }
            )

            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setOnDismissListener {
                lifecycleOwner.onDestroy()
                // Complete with CancellationException if dismissed without a result
                // (e.g. back button when directlyDismissable = true)
                resultFuture.completeExceptionally(CancellationException("Dialog dismissed without result"))
            }
            show()
        }
    }

    if (Looper.myLooper() == Looper.getMainLooper()) {
        showDialog()
    } else {
        Handler(Looper.getMainLooper()).post { showDialog() }
    }

    return runCatching { resultFuture.get() }
}

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    this.apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}
