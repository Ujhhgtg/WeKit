package dev.ujhhgtg.wekit.hooks.items.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import com.composables.icons.materialsymbols.outlined.Bomb
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.wekit.activity.StubFragmentActivity
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.net.WePacketHelper
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.coerceToInt
import dev.ujhhgtg.wekit.utils.fileExtension
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.outputStream
import android.widget.Button as AndroidButton

@HookItem(
    path = "聊天/聊天输入栏增强",
    description = "为聊天输入栏添加更多功能\n1. 在聊天界面长按「发送」或「加号菜单」按钮打开菜单\n菜单功能: 「发送卡片消息」「@所有人」\n2. 长按「语音」按钮发送自定义语音文件 (SILK 或 MP3)"
)
object ChatInputBarEnhancements : SwitchHookItem() {

    lateinit var currentConv: String

    override fun onEnable() {
        ChatFooter::class.resolve().apply {
            firstConstructor {
                parameters(Context::class, AttributeSet::class, Int::class)
            }
                .hookAfter {
                    val chatFooter = thisObject as ChatFooter
                    val searchedView = chatFooter.findViewByChildIndexes<View>(0)!!
                    val imgButtons = searchedView.findViewsWhich<ImageButton> { view ->
                        view.javaClass.simpleName == "WeImageButton"
                    }
                    val voiceButton = imgButtons.first()
                    val menuButton = imgButtons.last()
                    val sendButton = searchedView.findViewWhich<AndroidButton> { view ->
                        view.javaClass.name == "android.widget.Button" && run {
                            val text = (view as AndroidButton).text?.toString()?.trim() ?: ""
                            text == "发送" || text.equals("send", ignoreCase = true)
                        }
                    }!!

                    voiceButton.setOnLongClickListener { view ->
                        selectAndSendVoice(view.context, currentConv)
                        return@setOnLongClickListener true
                    }

                    listOf(menuButton, sendButton).forEach {
                        it.setOnLongClickListener { view ->
                            val context = view.context

                            showComposeDialog(context) {
                                AlertDialogContent(
                                    title = { Text("聊天功能") },
                                    text = {
                                        Column {
                                            ActionItem(
                                                icon = MaterialSymbols.Outlined.Voice_chat,
                                                label = "发送语音文件"
                                            ) {
                                                onDismiss()
                                                selectAndSendVoice(context, currentConv)
                                            }

                                            ActionItem(
                                                icon = MaterialSymbols.Outlined.Send_time_extension,
                                                label = "发送卡片消息"
                                            ) {
                                                onDismiss()
                                                val currentConv = currentConv
                                                val content = chatFooter.lastText

                                                if (content.isEmpty()) {
                                                    showToast("输入内容为空!")
                                                    return@ActionItem
                                                }

                                                val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                                                if (!isSuccess) {
                                                    showToast("发送卡片消息失败, 请检查格式")
                                                    return@ActionItem
                                                }

                                                chatFooter.findViewWhich<EditText> { view is EditText }?.setText("")
                                            }

                                            ActionItem(
                                                icon = MaterialSymbols.Outlined.Alternate_email,
                                                label = "@所有人"
                                            ) {
                                                onDismiss()

                                                if (!currentConv.endsWith("@chatroom")) {
                                                    showToast("只能在群组里使用!")
                                                    return@ActionItem
                                                }

                                                val contacts = WeDatabaseApi
                                                    .getGroupMembers(currentConv)
                                                    .filter { c -> c.wxId != WeApi.selfWxId }
                                                val content = chatFooter.lastText

                                                val reqBody = buildJsonObject {
                                                    put("1", 1)
                                                    putJsonObject("2") {
                                                        putJsonObject("1") {
                                                            put("1", currentConv)
                                                        }
                                                        put("2", contacts.joinToString("") { c ->
                                                            "@${c.nickname} "
                                                        } + content)
                                                        put("3", 1)
                                                        put("4", System.currentTimeMillis() / 1000)
                                                        put("5", -388413336)
                                                        put(
                                                            "6", """
                                                                        <msgsource><atuserlist><![CDATA[${contacts.joinToString(",") { c -> c.wxId }}]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>
                                                                    """.trimIndent()
                                                        )
                                                    }
                                                }

                                                WePacketHelper.sendCgi(
                                                    "/cgi-bin/micromsg-bin/newsendmsg",
                                                    522,
                                                    0,
                                                    0,
                                                    reqBody.toString()
                                                ) {
                                                    onSuccess { _, _ ->
                                                        showToast("已发送! (自己无法看到该消息)")
                                                    }
                                                }
                                            }

                                            ActionItem(
                                                icon = MaterialSymbols.Outlined.Bomb,
                                                label = "发送闪退贴纸表情",
                                            ) {

                                            }
                                        }
                                    })
                            }
                            return@setOnLongClickListener true
                        }
                    }
                }

            firstMethod {
                name = "setUserName"
            }.hookAfter {
                val conv = args[0] as? String
                if (!conv.isNullOrEmpty()) {
                    currentConv = conv
                }
            }
        }
    }
}

private fun selectAndSendVoice(context: Context, currentConv: String) {
    StubFragmentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val tempPath = KnownPaths.moduleCache / "voice_tmp.${uri.fileExtension}"
                contentResolver.openInputStream(uri)!!.use { input ->
                    tempPath.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val mimeType = contentResolver.getType(uri) ?: return@launch
                val isSilk = mimeType == "audio/amr"
                showToastSuspend("语音文件准备完成")
                val durationMs = AudioUtils.getDurationMs(tempPath.absolutePathString())

                withContext(Dispatchers.Main) {
                    finish()
                    showComposeDialog(context) {
                        var durationInput by remember { mutableStateOf(durationMs.toString()) }
                        AlertDialogContent(
                            title = { Text("发送语音文件") },
                            text = {
                                TextField(
                                    value = durationInput,
                                    onValueChange = { durationInput = it.filter { c -> c.isDigit() } },
                                    label = { Text("语音时长 (毫秒)") })
                            },
                            dismissButton = { TextButton(onDismiss) { Text("取消") } },
                            confirmButton = {
                                Button(onClick = {
                                    val durationMs = durationInput.toLongOrNull()
                                    if (durationMs == null) {
                                        showToast("时长格式不正确!")
                                        return@Button
                                    }

                                    var success = false
                                    if (isSilk) {
                                        showToast("正在发送 SILK...")
                                        success = WeMessageApi.sendVoice(
                                            currentConv,
                                            tempPath.absolutePathString(),
                                            durationMs.coerceToInt()
                                        )
                                    } else {
                                        showToast("正在将 MP3 转换为 SILK...")
                                        val tempSilkPath = KnownPaths.moduleCache / "voice_conv_tmp"
                                        val convSuccess = AudioUtils.mp3ToSilk(
                                            tempPath.absolutePathString(),
                                            tempSilkPath.absolutePathString()
                                        )
                                        if (convSuccess) {
                                            showToast("转换成功! 正在发送...")
                                            success = WeMessageApi.sendVoice(
                                                currentConv,
                                                tempSilkPath.absolutePathString(),
                                                durationMs.coerceToInt()
                                            )
                                        } else {
                                            showToast("转换失败! 查看日志以了解错误详情")
                                        }
                                        tempSilkPath.deleteIfExists()
                                    }
                                    showToast("语音发送${if (success) "成功" else "失败!"}")
                                    tempPath.deleteIfExists()
                                    onDismiss()
                                }) { Text("确定") }
                            })
                    }
                }
            }
        }
        // android couldn't distinguish AMR-extension SILK files, so we just use amr here
        importLauncher.launch(arrayOf("audio/amr", "audio/mpeg"))
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
