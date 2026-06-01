package dev.ujhhgtg.wekit.hooks.items.beautify

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.compose.material3.Text
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve

@HookItem(path = "界面美化/莫奈引擎", description = "为微信的部分组件启用动态壁纸取色 [需 SDK >= 31]")
object MonetEngine : ClickableHookItem() {

    val isActive
        get() = isEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val TAG = This.Class.simpleName

    val ON_PRIMARY by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HostInfo.application.run {
                getColor(
                    if (isDarkMode) android.R.color.system_accent1_200
                    else android.R.color.system_accent1_600
                )
            }
        } else DEFAULT_COLOR
    }

    val SECONDARY by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HostInfo.application.run {
                getColor(
                    if (isDarkMode) android.R.color.system_accent2_200
                    else android.R.color.system_accent2_600
                )
            }
        } else Color.LTGRAY
    }

    private const val DEFAULT_COLOR = -16268960


    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            WeLogger.w(TAG, "sdk < 31, not applying dynamic colors")
            return
        }

        "com.tencent.mm.ui.widget.MMSwitchBtn".toClass().constructors.forEach {
            it.hookAfter {
                thisObject.asResolver()
                    .field {
                        type = Int::class
                        superclass()
                    }.forEach { field ->
                        if (field.get()!! as Int == DEFAULT_COLOR)
                            field.set(ON_PRIMARY)
                    }
            }
        }

        Paint::class.resolve()
            .firstMethod { name = "setColor" }
            .hookBefore {
                val color = args[0] as Int
                if (color != DEFAULT_COLOR) return@hookBefore
                args[0] = ON_PRIMARY
            }

        TextView::class.resolve()
            .firstMethod { name = "onAttachedToWindow" }.hookAfter {
                val editText = thisObject as? EditText? ?: return@hookAfter
                editText.apply {
                    textCursorDrawable?.apply {
                        setTint(ON_PRIMARY)
                        editText.textCursorDrawable = this
                    }

//                    backgroundTintList = ColorStateList.valueOf(ON_PRIMARY)

                    // android views are weird
                    val handle = textSelectHandle ?: return@apply
                    handle.mutate()
                    setTextSelectHandle(handle)
                    textSelectHandle!!.setTint(ON_PRIMARY)
                }
            }

        View::class.resolve().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
            val drawable = args[0] as? Drawable? ?: return@hookBefore
            if (drawable is ColorDrawable) {
                val color = drawable.color
                if (color == DEFAULT_COLOR) drawable.color = ON_PRIMARY
            }
        }

        // FIXME: probably a bit inefficient
        View::class.resolve().firstMethod { name = "onFinishInflate" }.hookAfter {
            val view = thisObject as View
            if (view is Button) {
                view.backgroundTintList = ColorStateList.valueOf(SECONDARY)
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("莫奈引擎") },
                text = {
                    Text("如果动态壁纸取色没有生效, 说明系统 Android 版本过低 (SDK < 31)")
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } })
        }
    }
}
