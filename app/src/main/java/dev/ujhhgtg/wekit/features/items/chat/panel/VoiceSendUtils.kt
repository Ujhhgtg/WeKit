package dev.ujhhgtg.wekit.features.items.chat.panel

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.MediaFileTypeDetector
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.coerceToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.outputStream

/**
 * Opens a system file picker to select an audio file and send it as a WeChat voice message.
 *
 * Extracted from [dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi] so that
 * [dev.ujhhgtg.wekit.features.items.chat.VoicePanel] can offer the same escape-hatch without
 * duplicating the logic.
 */
fun selectAndSendVoice(context: Context, currentConv: String) {
    TransparentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val tempPath = PanelPaths.panelCacheDir / "picked-${UUID.randomUUID()}.bin"
                val prepareResult = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempPath.outputStream().use(input::copyTo)
                    } ?: error(localizedChatString(R.string.chat_voice_file_read_selected_failed))
                    val format = MediaFileTypeDetector.detectAudio(tempPath)
                        ?: error(localizedChatString(R.string.chat_voice_file_unsupported_format))
                    val directSource = format == MediaFileTypeDetector.AudioFormat.SILK ||
                            format == MediaFileTypeDetector.AudioFormat.AMR
                    Triple(directSource, AudioUtils.getDurationMs(tempPath.absolutePathString()), tempPath)
                }
                if (prepareResult.isFailure) {
                    tempPath.deleteIfExists()
                    withContext(Dispatchers.Main) {
                        finish()
                        showToast(
                            prepareResult.exceptionOrNull()?.message
                                ?: localizedChatString(R.string.chat_voice_file_read_failed),
                        )
                    }
                    return@launch
                }
                val (isSilk, durationMs) = prepareResult.getOrThrow()
                showToastSuspend(localizedChatString(R.string.chat_voice_file_ready))

                withContext(Dispatchers.Main) {
                    finish()
                    showComposeDialog(context) {
                        DisposableEffect(tempPath) {
                            onDispose { tempPath.deleteIfExists() }
                        }
                        var durationInput by remember { mutableStateOf(durationMs.toString()) }
                        AlertDialogContent(
                            title = { Text(stringResource(R.string.chat_voice_file_send_title)) },
                            text = {
                                TextField(
                                    value = durationInput,
                                    onValueChange = { durationInput = it.filter { c -> c.isDigit() } },
                                    label = { Text(stringResource(R.string.chat_voice_file_duration_ms)) })
                            },
                            dismissButton = {
                                TextButton({
                                    tempPath.deleteIfExists()
                                    onDismiss()
                                }) { Text(stringResource(R.string.dialog_cancel)) }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    val durMs = durationInput.toLongOrNull()
                                    if (durMs == null) {
                                        showToast(localizedChatString(R.string.chat_voice_file_invalid_duration))
                                        return@Button
                                    }

                                    val tempSilkPath = PanelPaths.panelCacheDir / "picked-${UUID.randomUUID()}.silk"
                                    val success = try {
                                        if (isSilk) {
                                            showToast(localizedChatString(R.string.chat_voice_file_sending_silk))
                                            WeMessageApi.sendVoice(
                                                currentConv,
                                                tempPath.absolutePathString(),
                                                durMs.coerceToInt()
                                            )
                                        } else {
                                            showToast(localizedChatString(R.string.chat_voice_file_converting_silk))
                                            if (AudioUtils.anyToSilk(
                                                    tempPath.absolutePathString(),
                                                    tempSilkPath.absolutePathString(),
                                                )
                                            ) {
                                                WeMessageApi.sendVoice(
                                                    currentConv,
                                                    tempSilkPath.absolutePathString(),
                                                    durMs.coerceToInt(),
                                                )
                                            } else {
                                                showToast(localizedChatString(R.string.chat_voice_file_conversion_failed))
                                                false
                                            }
                                        }
                                    } finally {
                                        tempSilkPath.deleteIfExists()
                                        tempPath.deleteIfExists()
                                    }
                                    showToast(
                                        localizedChatString(
                                            if (success) R.string.chat_voice_file_send_success
                                            else R.string.chat_voice_file_send_failed,
                                        ),
                                    )
                                    onDismiss()
                                }) { Text(stringResource(R.string.dialog_confirm)) }
                            })
                    }
                }
            }
        }
        importLauncher.launch(
            arrayOf("*/*")
        )
    }
}
