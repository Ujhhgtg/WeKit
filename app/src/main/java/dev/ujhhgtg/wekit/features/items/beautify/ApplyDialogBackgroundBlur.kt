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
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
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

    override val declaredErrors: List<Int>
        get() =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                listOf(R.string.feature_error_requires_android_12)
            } else {
                emptyList()
            }

    private const val TAG = "ApplyDialogBackgroundBlur"

    const val KEY_BLUR_RADIUS = "blur_radius"
    const val DEFAULT_BLUR_RADIUS = 20

    private val classMmAlertDialog by dexClass {
        usingEqStrings("MicroMsg.MMAlertDialog", "dialog dismiss error!")
    }
    private val classMmProgressDialog by dexClass {
        usingEqStrings($$"com/tencent/mm/ui/widget/dialog/MMProgressDialog$Builder", "show")
    }
    private val classMmQuickDialog by dexClass {
        superClass {
            className = "android.app.Dialog"
        }
        field {
            type = "android.widget.TextView"
        }
        field {
            type = "int"
            modifiers = Modifier.STATIC or Modifier.FINAL
        }
        field {
            type {
                className = "com.tencent.mm.ui.widget.imageview.WeImageView"
            }
        }
        field {
            type = "android.widget.ProgressBar"
        }
        field {
            type = "android.view.View"
        }
        field {
            type = "int"
        }
        field {
            type = "boolean"
            modifiers = Modifier.FINAL
        }
    }

    override fun onEnable() {
        listOf(
                classMmAlertDialog.clazz,
                classMmProgressDialog.clazz,
                classMmQuickDialog.clazz,
                HalfScreenTransparentActivity::class.java,
                Dialog::class.java,
            )
            .forEach {
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
            attributes.blurBehindRadius = KvStore.getIntOrDef(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var blurRadius by remember {
                mutableIntStateOf(
                    KvStore.getIntOrDef(
                        KEY_BLUR_RADIUS,
                        DEFAULT_BLUR_RADIUS,
                    ),
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
                                    title =
                                        stringResource(
                                            R.string.beautify_dialog_blur_unsupported_hint,
                                        ),
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
                                        KvStore.putInt(KEY_BLUR_RADIUS, it)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            window.attributes.blurBehindRadius = it
                                            window.attributes =
                                                window
                                                    .attributes // trigger onWindowAttributesChanged
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
