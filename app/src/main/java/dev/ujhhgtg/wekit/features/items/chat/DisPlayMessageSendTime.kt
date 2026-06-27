package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Feature(name = "显示消息发送时间", categories = ["聊天"], description = "在头像周围显示消息发送时间")
object DisPlayMessageSendTime : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private const val WRAPPER_TAG = "wekit_message_send_time_2_wrapper"
    private const val TIME_TAG = "wekit_message_send_time_2"
    private const val KEY_FONT_SIZE = "display_msg_time_2_font_size"
    private const val KEY_FONT_COLOR = "display_msg_time_2_font_color"
    private const val KEY_POSITION = "display_msg_time_2_position"
    private const val KEY_PATTERN = "display_msg_time_2_pattern"
    private const val DEFAULT_FONT_SIZE = 10
    private const val DEFAULT_FONT_COLOR = "#FF8D8D8D"
    private const val DEFAULT_PATTERN = "HH:mm:ss"
    private const val POSITION_TOP = 0
    private const val POSITION_BOTTOM = 1
    private var fontSize by prefOption(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    private var fontColor by prefOption(KEY_FONT_COLOR, DEFAULT_FONT_COLOR)
    private var position by prefOption(KEY_POSITION, POSITION_TOP)
    private var pattern by prefOption(KEY_PATTERN, DEFAULT_PATTERN)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var fontSizeInput by remember {
                mutableStateOf(fontSize.toString())
            }
            var fontColorInput by remember { mutableStateOf(fontColor) }
            var selectedPosition by remember { mutableIntStateOf(position) }
            var patternInput by remember {
                mutableStateOf(pattern)
            }

            AlertDialogContent(
                title = { Text("消息时间样式") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = fontSizeInput,
                            onValueChange = { fontSizeInput = it.filter { c -> c.isDigit() } },
                            label = { Text("字体大小 (默认 10)") }
                        )
                        TextField(
                            value = fontColorInput,
                            onValueChange = { fontColorInput = it },
                            label = { Text("字体颜色 (Hex)") },
                            placeholder = { Text(DEFAULT_FONT_COLOR) }
                        )
                        TextField(
                            value = patternInput,
                            onValueChange = { patternInput = it },
                            label = { Text("时间格式 (Java)") },
                            placeholder = { Text(DEFAULT_PATTERN) }
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "位置:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = selectedPosition == POSITION_TOP,
                                onClick = { selectedPosition = POSITION_TOP },
                                label = { Text("头像上方") }
                            )
                            FilterChip(
                                selected = selectedPosition == POSITION_BOTTOM,
                                onClick = { selectedPosition = POSITION_BOTTOM },
                                label = { Text("头像下方") }
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val size = fontSizeInput.toIntOrNull() ?: DEFAULT_FONT_SIZE
                        try {
                            fontColorInput.toColorInt()
                        } catch (_: Exception) {
                            showToast("颜色格式错误")
                            return@Button
                        }
                        val pattern = patternInput.ifBlank { DEFAULT_PATTERN }
                        try {
                            SimpleDateFormat(pattern, Locale.getDefault())
                        } catch (_: Exception) {
                            showToast("时间格式错误")
                            return@Button
                        }
                        fontSize = size
                        fontColor = fontColorInput
                        position = selectedPosition
                        this@DisPlayMessageSendTime.pattern = pattern
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val avatar = findAvatar(view) ?: return
        val wrapper = ensureAvatarWrapper(avatar) ?: return
        val timeView = ensureTimeView(wrapper)

        if (avatar.visibility != View.VISIBLE) {
            timeView.visibility = View.GONE
            wrapper.visibility = avatar.visibility
            return
        }

        timeView.text = formatTime(msgInfo.createTime)
        timeView.textSize = fontSize.toFloat()
        timeView.setTextColor(resolveTextColor())
        positionTimeView(timeView, position, msgInfo.isSelfSender)
        timeView.visibility = View.VISIBLE
        wrapper.visibility = View.VISIBLE
        ensureItemPadding(view, position)
        disableClipping(wrapper)
    }

    private fun findAvatar(itemView: View): View? {
        itemView.findViewWithTag<View>(WRAPPER_TAG)?.let { wrapper ->
            if (wrapper is ViewGroup) {
                return findWrappedAvatar(wrapper)
            }
        }

        findAvatarByClassName(itemView)?.let { return it }

        val tag = itemView.tag ?: return null
        return runCatching {
            tag.reflekt()
                .firstField {
                    name = "avatarIV"
                    superclass()
                }
                .get() as? View
        }.getOrNull()
    }

    private fun findAvatarByClassName(itemView: View): View? {
        return itemView.findViewWhich { view ->
            val className = view.javaClass.name
            className.contains("MaskLayout") || className.contains("ChattingAvatarImageView")
        }
    }

    private fun findWrappedAvatar(wrapper: ViewGroup): View? {
        for (i in 0 until wrapper.childCount) {
            val child = wrapper.getChildAt(i)
            if (child.tag != TIME_TAG) return child
        }
        return null
    }

    private fun ensureAvatarWrapper(avatar: View): FrameLayout? {
        val parent = avatar.parent as? ViewGroup ?: return null
        if (parent is FrameLayout && parent.tag == WRAPPER_TAG) {
            return parent
        }

        val index = parent.indexOfChild(avatar)
        if (index < 0) return null

        val originalId = avatar.id
        val originalParams = avatar.layoutParams
        parent.removeView(avatar)

        val wrapper = FrameLayout(parent.context).apply {
            tag = WRAPPER_TAG
            clipChildren = false
            clipToPadding = false
            if (originalId != View.NO_ID) {
                id = originalId
                avatar.id = View.generateViewId()
            }
        }

        wrapper.addView(
            avatar,
            FrameLayout.LayoutParams(
                originalParams.width,
                originalParams.height
            ).apply {
                gravity = Gravity.CENTER
            }
        )
        parent.addView(wrapper, index, originalParams)
        parent.clipChildren = false
        parent.clipToPadding = false
        (parent.parent as? ViewGroup)?.clipChildren = false
        return wrapper
    }

    private fun ensureTimeView(wrapper: FrameLayout): TextView {
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        val timeView = wrapper.findViewWithTag<TextView>(TIME_TAG) ?: TextView(wrapper.context).apply {
            tag = TIME_TAG
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setSingleLine(true)
        }
        timeView.maxLines = 1
        timeView.setSingleLine(true)

        if (timeView.parent == null) {
            wrapper.addView(timeView, layoutParams)
        } else {
            timeView.layoutParams = layoutParams
        }
        return timeView
    }

    private fun positionTimeView(timeView: TextView, position: Int, isSelfSender: Boolean) {
        val layoutParams = timeView.layoutParams as? FrameLayout.LayoutParams ?: return
        val horizontalGravity = if (isSelfSender) Gravity.END else Gravity.START
        timeView.gravity = if (isSelfSender) Gravity.END else Gravity.START
        layoutParams.gravity = if (position == POSITION_TOP) {
            Gravity.TOP or horizontalGravity
        } else {
            Gravity.BOTTOM or horizontalGravity
        }
        timeView.layoutParams = layoutParams
        timeView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val offset = timeView.measuredHeight.toFloat()
        timeView.translationY = if (position == POSITION_TOP) -offset else offset
    }

    private fun formatTime(time: Long): String {
        val epochMs = if (time in 1..99_999_999_999L) time * 1000L else time
        val pattern = pattern.ifBlank { DEFAULT_PATTERN }
        val formatter = runCatching {
            SimpleDateFormat(pattern, Locale.getDefault())
        }.getOrElse {
            SimpleDateFormat(DEFAULT_PATTERN, Locale.getDefault())
        }
        return formatter.format(Date(epochMs))
    }

    private fun resolveTextColor(): Int {
        return try {
            fontColor.toColorInt()
        } catch (_: Exception) {
            Color.parseColor(DEFAULT_FONT_COLOR)
        }
    }

    private fun ensureItemPadding(view: View, position: Int) {
        val topPadding = if (position == POSITION_TOP) {
            maxOf(view.paddingTop, 12.dpToPx(view.context))
        } else {
            view.paddingTop
        }
        val bottomPadding = if (position == POSITION_BOTTOM) {
            maxOf(view.paddingBottom, 12.dpToPx(view.context))
        } else {
            view.paddingBottom
        }
        if (topPadding == view.paddingTop && bottomPadding == view.paddingBottom) return
        view.setPadding(
            view.paddingLeft,
            topPadding,
            view.paddingRight,
            bottomPadding
        )
    }

    private fun disableClipping(start: ViewGroup) {
        var currentParent: ViewGroup? = start
        while (currentParent != null) {
            currentParent.clipChildren = false
            currentParent.clipToPadding = false
            currentParent = currentParent.parent as? ViewGroup
        }
    }
}
