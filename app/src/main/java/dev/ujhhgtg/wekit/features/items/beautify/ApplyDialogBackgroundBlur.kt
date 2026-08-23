package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.halfscreen.HalfScreenTransparentActivity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Modifier

object ApplyDialogBackgroundBlur : ClickableFeature(), IResolveDex {

    override val technicalId = "对话框窗口级背景模糊"
    override val nameRes = R.string.feature_apply_dialog_background_blur_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_apply_dialog_background_blur_description

    private const val TAG = "ApplyDialogBackgroundBlur"

    const val KEY_BLUR_RADIUS = "blur_radius"
    const val DEFAULT_BLUR_RADIUS = 20

    private val classMmAlertDialog by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMAlertDialog", "dialog dismiss error!")
        }
    }
    private val classMmProgressDialog by dexClass {
        matcher {
            usingEqStrings($$"com/tencent/mm/ui/widget/dialog/MMProgressDialog$Builder", "show")
        }
    }
    private val classMmQuickDialog by dexClass {
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

    override fun onEnable() {
        listOf(
            classMmAlertDialog.clazz,
            classMmProgressDialog.clazz,
            classMmQuickDialog.clazz,
            HalfScreenTransparentActivity::class.java,
            Dialog::class.java
        ).forEach {
            it.reflekt()
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

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var blurRadius by remember {
                mutableIntStateOf(
                    WePrefs.getIntOrDef(
                        KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS
                    )
                )
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_dialog_blur_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_dialog_blur_unsupported_hint),
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.beautify_dialog_blur_radius),
                                    value = blurRadius,
                                    startInt = 5,
                                    endInt = 30,
                                    stepSize = 1,
                                    valueSuffix = "px",
                                    onValueChange = {
                                        blurRadius = it
                                        WePrefs.putInt(KEY_BLUR_RADIUS, it)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            window.attributes.blurBehindRadius = it
                                            window.attributes = window.attributes // trigger onWindowAttributesChanged
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }
}
