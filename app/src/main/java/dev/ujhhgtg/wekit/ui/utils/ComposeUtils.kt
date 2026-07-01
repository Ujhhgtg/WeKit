package dev.ujhhgtg.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity

fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val ctx = CommonContextWrapper.create(context)

    val dialog = Dialog(
        ctx,
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )
    val lifecycleOwner = XposedLifecycleOwner.create()

    // 截取当前 Activity 画面作为液态玻璃背景
    val screenshot = runCatching {
        val activity = getTopMostActivity() ?: return@runCatching null
        val decorView = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        decorView.draw(canvas)
        bitmap
    }.getOrNull().also {
        if (it == null) WeLogger.w("ComposeUtils", "screenshot failed, liquid glass disabled")
    }

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        setCancelable(directlyDismissable)
        val scope = ShowComposeDialogScope(ctx, this, window!!, ::dismiss)

        setContentView(
            ComposeView(ctx).apply {
                setLifecycleOwner(lifecycleOwner)
                setContent {
                    CompositionLocalProvider(LocalContext provides ctx) {
                        AppTheme {
                            if (screenshot != null) {
                                LiquidGlassBox(screenshot, scope, content)
                            } else {
                                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.Center)) {
                                    scope.content()
                                }
                            }
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setOnDismissListener { lifecycleOwner.onDestroy() }
        show()
    }
}

@Composable
private fun LiquidGlassBox(
    screenshot: Bitmap,
    scope: ShowComposeDialogScope,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val imageBitmap = remember(screenshot) { screenshot.asImageBitmap() }
    val backdrop = rememberCanvasBackdrop {
        drawImage(imageBitmap)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(28.dp) },
                effects = {
                    // 1. 基础高斯模糊 — 磨砂效果
                    blur(24f)
                    // 2. 镜头折射 — 模拟水滴凸透镜效应
                    lens(22f, 44f, depthEffect = true, chromaticAberration = true)
                    // 3. 辉光增艳 — 对应 LGGC 的 saturate(160%)
                    vibrancy()
                    // 4. 色彩控制 — 提亮+高饱和，让玻璃更通透
                    colorControls(brightness = 0.18f, saturation = 1.6f)
                },
                // 5. 边缘高光 — 对应 LGGC 的 inset white box-shadow（水滴感核心）
                highlight = { Highlight.Plain },
                // 6. 底部投影 — 悬浮感
                shadow = {
                    Shadow(
                        radius = 42.dp,
                        color = CColor.Black.copy(alpha = 0.18f),
                        alpha = 1f
                    )
                },
                // 7. 内阴影 — 内部深度感
                innerShadow = {
                    InnerShadow(
                        radius = 8.dp,
                        color = CColor.Black.copy(alpha = 0.06f),
                        alpha = 1f
                    )
                },
                onDrawSurface = {
                    // 极浅底色，让玻璃保持通透（LGGC 的 --lggc-bg: rgba(255,255,255,0.08)）
                    drawRect(CColor.White.copy(alpha = 0.12f))
                }
            )
    ) {
        // 内容直接叠加在玻璃层上
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
        ) {
            scope.content()
        }
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