package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferRespProto
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Feature(
    id = "检测单向删除好友",
    nameRes = "feature_detect_deleted_friends_name",
    categoryIds = [FeatureCategoryIds.CONTACTS_GROUPS],
    descriptionRes = "feature_detect_deleted_friends_description",
)
object DetectDeletedFriends : ClickableFeature() {

    override val noSwitchWidget = true

    private const val TAG = "DetectDeletedFriends"

    private sealed class DialogPhase {
        data object Idle : DialogPhase()
        data class Scanning(
            val completed: MutableIntState,
            val total: Int,
            val abnormalFriends: MutableList<WeContact> = mutableListOf()
        ) : DialogPhase()

        data class Done(val friends: List<WeContact>) : DialogPhase()
        data class SelectLabel(
            val friends: List<WeContact>,
            val suggestedLabelName: String
        ) : DialogPhase()

        data class Marking(
            val friends: List<WeContact>,
            val labelName: String,
            val completed: MutableIntState,
            val total: Int
        ) : DialogPhase()

        data class ConfirmDelete(
            val allFriends: List<WeContact>,
            val targets: List<WeContact>
        ) : DialogPhase()

        data class Deleting(
            val allFriends: List<WeContact>,
            val targets: List<WeContact>,
            val completed: MutableIntState,
            val total: Int,
            val failed: MutableList<WeContact> = mutableListOf()
        ) : DialogPhase()
    }

    override fun onClick(context: ComponentActivity) {
        val friends = WeDatabaseApi.getFriends().filter { c ->
            c.type != 2051 && c.type != 2049 && c.wxId != WeApi.selfWxId
        }

        showComposeDialog(context) {
            var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
            var availableLabels by remember { mutableStateOf<List<WeContactLabelApi.ContactLabel>?>(null) }

            LaunchedEffect(phase) {
                if (phase is DialogPhase.Scanning) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val scanningPhase = phase as DialogPhase.Scanning
                        val abnormalFriends = scanningPhase.abnormalFriends
                        for (friend in friends) {
                            // detect whether user quitted halfway
                            if (phase !is DialogPhase.Scanning) {
                                break
                            }

                            WePacketHelper.sendCgi(
                                "/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0,
                                BeforeTransferReqProto(userName = friend.wxId).encode()
                            ) {
                                onSuccess { bytes ->
                                    val realName = bytes
                                        ?.let { BeforeTransferRespProto.decode(it) }
                                        ?.maskedRealName
                                    WeLogger.d(TAG, "realName=$realName")
                                    if (realName == null) {
                                        synchronized(abnormalFriends) {
                                            // TODO: figure out status, might have to perform another request
                                            //       update: seems that wechat modified their server-side logic
                                            //       now it is impossible to tell the difference
                                            abnormalFriends += friend
                                        }
                                    }
                                    scanningPhase.completed.intValue++
                                }

                                onFailure { errType, errCode, errMsg ->
                                    WeLogger.w(TAG, "failed friend ${friend.wxId}: $errType, $errCode, $errMsg")
                                    scanningPhase.completed.intValue++
                                }
                            }
                            // seems like WeChat's server rate limits requests
                            delay(1.seconds)
                        }

                        if (phase is DialogPhase.Scanning) {
                            phase = DialogPhase.Done(synchronized(abnormalFriends) { abnormalFriends.toList() })
                            dialog.setCancelable(true)
                        }
                    }
                } else if (phase is DialogPhase.SelectLabel) {
                    dialog.setCancelable(true)
                    availableLabels = null
                    CoroutineScope(Dispatchers.IO).launch {
                        availableLabels = WeContactLabelApi.getAllLabels()
                    }
                } else if (phase is DialogPhase.Marking) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val markingPhase = phase as DialogPhase.Marking
                        // ensure the target label exists before tagging; createLabel is a no-op
                        // when the label is already present, otherwise it dispatches the
                        // addcontactlabel netscene and waits for the server-assigned id to land
                        val labelId = WeContactLabelApi.createLabel(markingPhase.labelName)
                        if (labelId == null) {
                            if (phase is DialogPhase.Marking) {
                                phase = DialogPhase.Done(markingPhase.friends)
                                dialog.setCancelable(true)
                                showToastSuspend(
                                    context,
                                    context.localizedContactsString(
                                        R.string.contacts_detect_create_label_failed,
                                        markingPhase.labelName,
                                    ),
                                )
                            }
                            return@launch
                        }

                        for (friend in markingPhase.friends) {
                            // detect whether user quitted halfway
                            if (phase !is DialogPhase.Marking) {
                                break
                            }

                            // additive: keep existing labels and append the target one
                            val existing = WeContactLabelApi.getLabelNamesForContact(friend.wxId)
                            if (markingPhase.labelName !in existing) {
                                WeContactLabelApi.modifyLabel(
                                    friend.wxId,
                                    existing + markingPhase.labelName
                                )
                            }
                            markingPhase.completed.intValue++
                            // avoid hammering the netscene dispatcher
                            delay(1.seconds)
                        }

                        if (phase is DialogPhase.Marking) {
                            phase = DialogPhase.Done(markingPhase.friends)
                            dialog.setCancelable(true)
                            showToastSuspend(
                                context,
                                context.localizedContactsString(R.string.contacts_detect_marking_done),
                            )
                        }
                    }
                } else if (phase is DialogPhase.Deleting) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val deletingPhase = phase as DialogPhase.Deleting
                        val deleted = mutableSetOf<String>()
                        for (friend in deletingPhase.targets) {
                            // detect whether user quitted halfway
                            if (phase !is DialogPhase.Deleting) {
                                break
                            }

                            val ok = WeContactApi.deleteContact(friend.wxId)
                            if (ok) {
                                deleted += friend.wxId
                            } else {
                                synchronized(deletingPhase.failed) { deletingPhase.failed += friend }
                            }
                            deletingPhase.completed.intValue++
                            // seems like WeChat's server rate limits requests
                            delay(1.seconds)
                        }

                        if (phase is DialogPhase.Deleting) {
                            // drop successfully deleted friends from the result list
                            val remaining = deletingPhase.allFriends.filter { it.wxId !in deleted }
                            val failedCount = synchronized(deletingPhase.failed) { deletingPhase.failed.size }
                            phase = DialogPhase.Done(remaining)
                            dialog.setCancelable(true)
                            showToastSuspend(
                                context,
                                context.localizedContactsQuantity(
                                    R.plurals.contacts_detect_delete_done,
                                    deleted.size,
                                    deleted.size,
                                    failedCount,
                                ),
                            )
                        }
                    }
                }
            }

            AlertDialogContent(
                title = {
                    Text(
                        text = stringResource(
                            if (phase is DialogPhase.Idle) R.string.contacts_detect_warning_title
                            else R.string.feature_detect_deleted_friends_name,
                        ),
                    )
                },
                text = {
                    when (phase) {
                        is DialogPhase.Idle -> Text(text = stringResource(R.string.contacts_detect_warning_message))

                        is DialogPhase.Scanning -> {
                            val completed by (phase as DialogPhase.Scanning).completed
                            val total = (phase as DialogPhase.Scanning).total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_scanning,
                                        total,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.Done -> {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            Text(
                                pluralStringResource(
                                    R.plurals.contacts_detect_scan_done,
                                    abnormalFriends.size,
                                    abnormalFriends.size,
                                ),
                            )
                            LazyColumn {
                                items(abnormalFriends) { friend ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            WeApi.openContact(context, friend.wxId, WeApi.OpenContactDestination.HOMEPAGE)
                                        },
                                        trailingContent = {
                                            IconButton(onClick = {
                                                phase = DialogPhase.ConfirmDelete(
                                                    allFriends = abnormalFriends,
                                                    targets = listOf(friend)
                                                )
                                            }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.action_delete),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        },
                                        supportingContent = {
                                            Column {
                                                Text(stringResource(R.string.contacts_detect_status_abnormal))
                                                Text(stringResource(R.string.contacts_detect_nickname, friend.nickname))
                                                Text(stringResource(R.string.contacts_detect_remark, friend.remarkName))
                                                Text(stringResource(R.string.contacts_wechat_id_value, friend.wxId))
                                                Text(stringResource(R.string.contacts_detect_wechat_number, friend.customWxId))
                                            }
                                        },
                                        content = { Text(friend.displayName) },
                                    )
                                }
                            }
                        }

                        is DialogPhase.ConfirmDelete -> {
                            val confirmPhase = phase as DialogPhase.ConfirmDelete
                            Text(
                                pluralStringResource(
                                    R.plurals.contacts_detect_confirm_delete,
                                    confirmPhase.targets.size,
                                    confirmPhase.targets.size,
                                ),
                            )
                        }

                        is DialogPhase.Deleting -> {
                            val completed by (phase as DialogPhase.Deleting).completed
                            val total = (phase as DialogPhase.Deleting).total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_deleting,
                                        total,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.SelectLabel -> {
                            val selectPhase = phase as DialogPhase.SelectLabel
                            val labels = availableLabels
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_select_label,
                                        selectPhase.friends.size,
                                        selectPhase.friends.size,
                                    ),
                                )
                                if (labels == null) {
                                    LinearWavyProgressIndicator()
                                } else {
                                    LazyColumn {
                                        // always offer creating a fresh, timestamped label
                                        item {
                                            ListItem(
                                                modifier = Modifier.clickable {
                                                    phase = DialogPhase.Marking(
                                                        friends = selectPhase.friends,
                                                        labelName = selectPhase.suggestedLabelName,
                                                        completed = mutableIntStateOf(0),
                                                        total = selectPhase.friends.size
                                                    )
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        MaterialSymbols.Outlined.Add,
                                                        contentDescription = stringResource(R.string.contacts_detect_new_label),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                },
                                                supportingContent = { Text(stringResource(R.string.contacts_detect_new_label)) },
                                                content = { Text(selectPhase.suggestedLabelName) },
                                            )
                                        }
                                        items(labels) { label ->
                                            ListItem(
                                                modifier = Modifier.clickable {
                                                    phase = DialogPhase.Marking(
                                                        friends = selectPhase.friends,
                                                        labelName = label.labelName,
                                                        completed = mutableIntStateOf(0),
                                                        total = selectPhase.friends.size
                                                    )
                                                },
                                                content = { Text(label.labelName) },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is DialogPhase.Marking -> {
                            val completed by (phase as DialogPhase.Marking).completed
                            val total = (phase as DialogPhase.Marking).total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_marking,
                                        total,
                                        (phase as DialogPhase.Marking).labelName,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }
                    }
                },
                dismissButton = when (phase) {
                    is DialogPhase.Idle -> {
                        {
                            TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                        }
                    }

                    is DialogPhase.Scanning -> {
                        {
                            TextButton(onClick = {
                                val scanningPhase = phase as DialogPhase.Scanning
                                // display current snapshot immediately
                                val foundSoFar = synchronized(scanningPhase.abnormalFriends) {
                                    scanningPhase.abnormalFriends.toList()
                                }
                                phase = DialogPhase.Done(foundSoFar)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.SelectLabel -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.SelectLabel).friends)
                            }) { Text(stringResource(R.string.contacts_detect_back)) }
                        }
                    }

                    is DialogPhase.Marking -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.Marking).friends)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.ConfirmDelete -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.ConfirmDelete).allFriends)
                            }) { Text(stringResource(R.string.dialog_cancel)) }
                        }
                    }

                    is DialogPhase.Deleting -> {
                        {
                            TextButton(onClick = {
                                // stop the loop; the running coroutine won't transition once phase changes,
                                // so flip to Done here with friends not yet deleted left in place
                                val deletingPhase = phase as DialogPhase.Deleting
                                phase = DialogPhase.Done(deletingPhase.allFriends)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.Done -> null
                },
                confirmButton = when (phase) {
                    is DialogPhase.Idle -> {
                        {
                            Button(onClick = {
                                phase = DialogPhase.Scanning(mutableIntStateOf(0), friends.size)
                            })
                            { Text(stringResource(R.string.dialog_confirm)) }
                        }
                    }

                    is DialogPhase.Done -> {
                        {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            if (abnormalFriends.isNotEmpty()) {
                                TextButton(onClick = {
                                    availableLabels = null
                                    phase = DialogPhase.SelectLabel(
                                        friends = abnormalFriends,
                                        suggestedLabelName = context.localizedContactsString(
                                            R.string.contacts_detect_suggested_label,
                                            formatEpoch(System.currentTimeMillis(), includeDate = true),
                                        ),
                                    )
                                }) { Text(stringResource(R.string.contacts_detect_mark_label)) }
                                TextButton(onClick = {
                                    phase = DialogPhase.ConfirmDelete(
                                        allFriends = abnormalFriends,
                                        targets = abnormalFriends
                                    )
                                }) { Text(stringResource(R.string.contacts_detect_delete_all)) }
                            }
                            Button(onClick = {
                                val text = abnormalFriends.joinToString("\n\n") { friend ->
                                    buildString {
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_status_abnormal))
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_nickname, friend.nickname))
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_remark, friend.remarkName))
                                        appendLine(context.localizedContactsString(R.string.contacts_wechat_id_value, friend.wxId))
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_wechat_number, friend.customWxId))
                                    }
                                }
                                copyToClipboard(context, text)
                                showToast(context, context.localizedContactsString(R.string.contacts_copied))
                            }) { Text(stringResource(R.string.contacts_copy)) }
                        }
                    }

                    is DialogPhase.ConfirmDelete -> {
                        {
                            Button(onClick = {
                                val confirmPhase = phase as DialogPhase.ConfirmDelete
                                phase = DialogPhase.Deleting(
                                    allFriends = confirmPhase.allFriends,
                                    targets = confirmPhase.targets,
                                    completed = mutableIntStateOf(0),
                                    total = confirmPhase.targets.size
                                )
                            }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }

                    else -> null
                }
            )
        }
    }
}
