package dev.ujhhgtg.wekit.features.items.contacts

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    name = "限制群成员昵称长度",
    categories = ["聊天"],
    description = "限制群聊中成员昵称的最大显示长度（不计模块注入的文本）"
)
object LimitGroupMemberNicknameLength : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private var maxNicknameLength by prefOption("max_nickname_length", 10)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(maxNicknameLength.toString()) }

            AlertDialogContent(
                title = { Text("限制群成员昵称长度") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it.filter { ch -> ch.isDigit() } },
                            label = { Text("最大字符数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val lenInput = value.toIntOrNull()
                        if (lenInput == null || lenInput <= 0) {
                            showToast("数字格式不正确!")
                            return@Button
                        }
                        maxNicknameLength = lenInput
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return

        val textView = view.tag.reflekt()
            .firstField { name = "userTV"; superclass() }
            .get() as? TextView? ?: return

        val currentText = textView.text ?: return
        val maxLen = maxNicknameLength
        if (maxLen <= 0) return

        val nicknameRange = currentText.groupMemberNicknameRange()
        val pureStart = nicknameRange.start
        val pureEnd = nicknameRange.endExclusive

        if (pureStart >= pureEnd) return

        // 提取出真正原始的昵称部分
        val pureNickname = currentText.subSequence(pureStart, pureEnd).toString()

        // 判断纯昵称是否超出限制
        if (pureNickname.length > maxLen) {
            val truncated = pureNickname.take(maxLen) + "..."

            // 重新组装 Spannable，这样可以完美保留原有前后缀的各类 Span（样式、背景等）
            val sb = SpannableStringBuilder()

            // 拼接原有的 Prefix 及其 Span
            if (pureStart > 0) {
                sb.append(currentText.subSequence(0, pureStart))
            }

            // 拼接截断后的核心昵称
            sb.append(truncated)

            // 拼接原有的 Suffix 及其 Span
            if (pureEnd < currentText.length) {
                sb.append(currentText.subSequence(pureEnd, currentText.length))
            }

            textView.text = sb
        }
    }
}
