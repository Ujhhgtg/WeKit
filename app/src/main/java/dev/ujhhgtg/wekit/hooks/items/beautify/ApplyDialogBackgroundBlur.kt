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
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import kotlin.math.roundToInt

@HookItem(path = "界面美化/对话框窗口级背景模糊", description = "为模块与微信的对话框添加窗口级模糊处理 [需 SDK >= 31]")
object ApplyDialogBackgroundBlur : ClickableHookItem(), IResolvesDex {

    private val TAG = nameOf(ApplyDialogBackgroundBlur)

    const val KEY_BLUR_RADIUS = "blur_radius"
    const val DEFAULT_BLUR_RADIUS = 20

    private val classMmAlertDialog by dexClass()
    private val classMmProgressDialog by dexClass()
    private val classMmQuickDialog by dexClass()

    override fun onEnable() {
        listOf(
            classMmAlertDialog.clazz,
            classMmProgressDialog.clazz,
            classMmQuickDialog.clazz,
            HalfScreenTransparentActivity::class.java,
            Dialog::class.java
        ).forEach {
            it.asResolver()
                .firstMethod {
                    name = "onCreate"
                }
                .hookBefore {
                    val thiz = thisObject
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

    override fun resolveDex(dexKit: DexKitBridge) {
        classMmAlertDialog.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MMAlertDialog", "dialog dismiss error!")
            }
        }

        classMmProgressDialog.find(dexKit) {
            matcher {
                usingEqStrings($$"com/tencent/mm/ui/widget/dialog/MMProgressDialog$Builder", "show")
            }
        }

        classMmQuickDialog.find(dexKit) {
            matcher {
                superClass("android.app.Dialog")
                addField {
                    type = "int"
                    modifiers(Modifier.STATIC or Modifier.FINAL)
                }
                addFieldForType("android.widget.TextView")
                addFieldForType("com.tencent.mm.ui.widget.imageview.WeImageView")
                addFieldForType("android.widget.ProgressBar")
                addFieldForType("android.view.View")
                addFieldForType("int")
                addField {
                    type = "boolean"
                    modifiers(Modifier.FINAL)
                }
            }
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

                    Text("如果本对话框背景没有模糊, 说明系统 Android 版本过低 (SDK < 31) 或未在开发者选项中启用")
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("模糊半径 (实时生效)") },
                        supportingContent = {
                            IntSlider(
                                blurRadius,
                                {
                                    blurRadius = it
                                    WePrefs.putInt(KEY_BLUR_RADIUS, blurRadius)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        window.attributes.blurBehindRadius = blurRadius
                                        window.attributes = window.attributes // trigger onWindowAttributesChanged
                                    } else {
                                        WeLogger.w(TAG, "sdk < 31, not applying blur behind dialog")
                                    }
                                },
                                5..30
                            )
                        }
                    )
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } })
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
        Text(text = "当前值: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = valueRange.last - valueRange.first - 1
        )
    }
}
