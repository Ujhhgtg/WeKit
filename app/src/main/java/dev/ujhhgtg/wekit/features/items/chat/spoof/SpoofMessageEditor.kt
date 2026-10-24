package dev.ujhhgtg.wekit.features.items.chat.spoof

import android.content.Context
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Notes
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Photo_library
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates, edits and removes a message's spoof from the chat's message menu. */
object SpoofMessageEditor {
    private const val TAG = "SpoofMessageEditor"

    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(context: Context, target: SpoofTarget) {
        showComposeDialog(context) {
            val existing = remember {
                SpoofedMessageStore[target.key]?.takeIf { it.createTime == target.createTime }
            }
            var draft by remember {
                mutableStateOf(
                    when (target.kind) {
                        SpoofKind.TEXT -> existing?.text ?: target.original!!

                        SpoofKind.TRANSFER -> existing?.amount ?: amountNumber(target.original!!)

                        SpoofKind.IMAGE,
                        SpoofKind.VIDEO -> ""
                    },
                )
            }
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            fun perform(doneMessage: Int, action: suspend () -> Unit) {
                busy = true
                failed = false
                dialog.setCancelable(false)
                scope.launch {
                    try {
                        action()
                        showToast(localizedContext.getString(doneMessage))
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to update the spoof of ${target.key}", e)
                        failed = true
                    } finally {
                        busy = false
                        dialog.setCancelable(true)
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(target.kind.titleRes)) },
                text = {
                    DefaultColumn {
                        when (target.kind) {
                            SpoofKind.IMAGE -> {
                                Text(stringResource(R.string.spoof_message_image_hint))
                            }

                            SpoofKind.VIDEO -> {
                                Text(stringResource(R.string.spoof_message_video_hint))
                            }

                            else -> {
                                SpoofValueField(
                                    target.kind,
                                    target.original,
                                    draft,
                                    { draft = it },
                                    enabled = !busy,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.spoof_message_local_only),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        if (failed) {
                            Text(
                                stringResource(R.string.spoof_message_save_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && isSpoofValueValid(target.kind, draft),
                        onClick = {
                            when (target.kind) {
                                SpoofKind.TEXT -> {
                                    perform(R.string.spoof_message_saved) {
                                        SpoofedMessageStore.saveText(target, draft)
                                    }
                                }

                                SpoofKind.TRANSFER -> {
                                    perform(R.string.spoof_message_saved) {
                                        SpoofedMessageStore.saveAmount(
                                            target,
                                            normalizeSpoofAmount(draft)!!,
                                        )
                                    }
                                }

                                SpoofKind.IMAGE,
                                SpoofKind.VIDEO -> {
                                    onDismiss()
                                    pickMedia(context, target)
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                when (target.kind) {
                                    SpoofKind.IMAGE -> R.string.spoof_message_choose_image
                                    SpoofKind.VIDEO -> R.string.spoof_message_choose_video
                                    else -> R.string.action_save
                                },
                            ),
                        )
                    }
                },
                dismissButton = {
                    Row {
                        if (existing != null) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    perform(R.string.spoof_message_removed) {
                                        SpoofedMessageStore.remove(target.key)
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(R.string.spoof_message_remove),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onDismiss, enabled = !busy) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                },
            )
        }
    }

    private fun pickMedia(context: Context, target: SpoofTarget) {
        TransparentActivity.launch(context) {
            val launcher =
                registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    finish()
                    if (uri != null) importMedia(target, uri)
                }
            launcher.launch(pickMediaRequest(target.kind))
        }
    }

    fun pickMediaRequest(kind: SpoofKind) =
        PickVisualMediaRequest(
            if (kind == SpoofKind.VIDEO) {
                ActivityResultContracts.PickVisualMedia.VideoOnly
            } else {
                ActivityResultContracts.PickVisualMedia.ImageOnly
            },
        )

    /** Imports picked media in the background; large videos take a while to copy into Room. */
    fun importMedia(target: SpoofTarget, uri: Uri) {
        showToast(localizedChatString(R.string.spoof_message_importing))
        importScope.launch {
            try {
                SpoofedMessageStore.saveMedia(HostInfo.application, target, uri)
                showToast(localizedChatString(R.string.spoof_message_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to import spoofed media for ${target.key}", e)
                showToast(localizedChatString(R.string.spoof_message_save_failed))
            }
        }
    }
}

/** The spoofed value's input: the displayed text, or a transfer amount in the host's currency. */
@Composable
fun SpoofValueField(
    kind: SpoofKind,
    original: String?,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    when (kind) {
        SpoofKind.TEXT -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                label = { Text(stringResource(R.string.spoof_message_text_label)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SpoofKind.TRANSFER -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                label = { Text(stringResource(R.string.spoof_message_amount_label)) },
                prefix = { Text(amountCurrency(original!!)) },
                supportingText = {
                    Text(stringResource(R.string.spoof_message_original, original!!))
                },
                isError = !isSpoofValueValid(kind, value),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SpoofKind.IMAGE,
        SpoofKind.VIDEO -> {
            error("$kind spoofs are picked, not typed")
        }
    }
}

fun isSpoofValueValid(kind: SpoofKind, value: String) =
    when (kind) {
        SpoofKind.TEXT -> value.isNotEmpty()

        SpoofKind.TRANSFER -> normalizeSpoofAmount(value) != null

        SpoofKind.IMAGE,
        SpoofKind.VIDEO -> true
    }

val SpoofKind.titleRes
    get() =
        when (this) {
            SpoofKind.TEXT -> R.string.spoof_message_title_text
            SpoofKind.IMAGE -> R.string.spoof_message_title_image
            SpoofKind.VIDEO -> R.string.spoof_message_title_video
            SpoofKind.TRANSFER -> R.string.spoof_message_title_transfer
        }

val SpoofKind.labelRes
    get() =
        when (this) {
            SpoofKind.TEXT -> R.string.spoof_kind_text
            SpoofKind.IMAGE -> R.string.spoof_kind_image
            SpoofKind.VIDEO -> R.string.spoof_kind_video
            SpoofKind.TRANSFER -> R.string.spoof_kind_transfer
        }

val SpoofKind.icon: ImageVector
    get() =
        when (this) {
            SpoofKind.TEXT -> MaterialSymbols.Outlined.Notes
            SpoofKind.IMAGE -> MaterialSymbols.Outlined.Photo_library
            SpoofKind.VIDEO -> MaterialSymbols.Outlined.Movie
            SpoofKind.TRANSFER -> MaterialSymbols.Outlined.Payments
        }
