package dev.ujhhgtg.wekit.hooks.items.beautify

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.tencent.mm.ui.halfscreen.HalfScreenTransparentActivity
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlin.math.roundToInt

@HookItem(path = "界面美化/对话框窗口级背景模糊", desc = "为模块与微信的对话框添加窗口级模糊处理")
object ApplyDialogBackgroundBlur : ClickableHookItem() {

    private val TAG = nameOf(ApplyDialogBackgroundBlur)

    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val DEFAULT_BLUR_RADIUS = 20

    override fun onEnable() {
        listOf(
            Dialog::class,
            HalfScreenTransparentActivity::class
        ).forEach {
            it.asResolver()
                .firstMethod {
                    name = "onCreate"
                }
                .hookBefore { param ->
                    val thiz = param.thisObject
                    if (thiz is Dialog) {
                        thiz.window?.let { w -> applyBlur(w) }
                    } else if (thiz is Activity) {
                        thiz.window?.let { w -> applyBlur(w) }
                    }
                }
        }
    }

    private fun applyBlur(window: Window) {
        window.apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WeLogger.w(TAG, "sdk < 31, not applying blur behind dialog")
                return@apply
            }

            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes.blurBehindRadius = WePrefs.getIntOrDef(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("对话框窗口级背景模糊") },
                text = {
                    var blurRadius by remember {
                        mutableIntStateOf(
                            WePrefs.getIntOrDef(
                                KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS
                            )
                        )
                    }

                    DefaultColumn {
                        Text("如果本对话框背景没有模糊, 说明系统 Android 版本过低 (SDK < 31) 或未在开发者选项中启用")
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("模糊半径 (即时生效)") },
                            supportingContent = {
                                IntSlider(
                                    blurRadius,
                                    {
                                        blurRadius = it
                                        WePrefs.putInt(KEY_BLUR_RADIUS, blurRadius)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            window.attributes.blurBehindRadius = blurRadius
                                            window.callback.onWindowAttributesChanged(window.attributes)
                                        } else {
                                            WeLogger.w(TAG, "sdk < 31, not applying blur behind dialog")
                                        }
                                    },
                                    5..30
                                )
                            }
                        )
                    }
                },
                dismissButton = { Button(onDismiss) { Text("关闭") } })
        }
    }

}

@Composable
private fun IntSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange = 0..100
) {
    Column {
        Text(text = "当前值: $value | 范围: $valueRange")
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = valueRange.last - valueRange.first - 1
        )
    }
}
