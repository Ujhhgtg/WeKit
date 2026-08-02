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

@Feature(name = "检测单向删除好友", categories = ["联系人与群组"], description = "批量扫描全部好友, 检测是否被对方单向删除")
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
                                showToastSuspend(context, "创建标签「${markingPhase.labelName}」失败")
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
                            showToastSuspend(context, "标记完成")
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
                                "删除完成: 成功 ${deleted.size}, 失败 $failedCount"
                            )
                        }
                    }
                }
            }

            AlertDialogContent(
                title = { Text(text = if (phase is DialogPhase.Idle) "警告" else "检测单向删除好友") },
                text = {
                    when (phase) {
                        is DialogPhase.Idle -> Text(text = "此功能可能导致账号异常, 确定要执行吗?")

                        is DialogPhase.Scanning -> {
                            val completed by (phase as DialogPhase.Scanning).completed
                            val total = (phase as DialogPhase.Scanning).total
                            DefaultColumn {
                                Text("正在扫描, 请稍等...\n已完成: $completed/$total")
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.Done -> {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            Text("扫描完成, 有 ${abnormalFriends.size} 个状态异常的好友")
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
                                                    contentDescription = "删除",
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        },
                                        supportingContent = {
                                            Column {
                                                Text("状态: 异常")
                                                Text("昵称: ${friend.nickname}")
                                                Text("备注: ${friend.remarkName}")
                                                Text("微信 ID: ${friend.wxId}")
                                                Text("微信号: ${friend.customWxId}")
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
                                "确定要删除选中的 ${confirmPhase.targets.size} 个好友吗?\n" +
                                        "此操作不可逆, 删除后聊天记录将被清除, 需对方重新添加."
                            )
                        }

                        is DialogPhase.Deleting -> {
                            val completed by (phase as DialogPhase.Deleting).completed
                            val total = (phase as DialogPhase.Deleting).total
                            DefaultColumn {
                                Text("正在删除好友, 请稍等...\n已完成: $completed/$total")
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.SelectLabel -> {
                            val selectPhase = phase as DialogPhase.SelectLabel
                            val labels = availableLabels
                            DefaultColumn {
                                Text("选择一个标签, 将应用到全部 ${selectPhase.friends.size} 个异常好友")
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
                                                        contentDescription = "新建标签",
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                },
                                                supportingContent = { Text("新建标签") },
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
                                Text("正在标记为「${(phase as DialogPhase.Marking).labelName}」, 请稍等...\n已完成: $completed/$total")
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }
                    }
                },
                dismissButton = when (phase) {
                    is DialogPhase.Idle -> {
                        {
                            TextButton(onDismiss) { Text("取消") }
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
                            }) { Text("终止") }
                        }
                    }

                    is DialogPhase.SelectLabel -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.SelectLabel).friends)
                            }) { Text("返回") }
                        }
                    }

                    is DialogPhase.Marking -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.Marking).friends)
                                dialog.setCancelable(true)
                            }) { Text("终止") }
                        }
                    }

                    is DialogPhase.ConfirmDelete -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.ConfirmDelete).allFriends)
                            }) { Text("取消") }
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
                            }) { Text("终止") }
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
                            { Text("确定") }
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
                                        suggestedLabelName = "WeKit_单删好友_${formatEpoch(System.currentTimeMillis(), includeDate = true)}"
                                    )
                                }) { Text("标记标签") }
                                TextButton(onClick = {
                                    phase = DialogPhase.ConfirmDelete(
                                        allFriends = abnormalFriends,
                                        targets = abnormalFriends
                                    )
                                }) { Text("全部删除") }
                            }
                            Button(onClick = {
                                val text = abnormalFriends.joinToString("\n\n") { friend ->
                                    buildString {
                                        appendLine("状态: 异常")
                                        appendLine("昵称: ${friend.nickname}")
                                        appendLine("备注: ${friend.remarkName}")
                                        appendLine("微信 ID: ${friend.wxId}")
                                        appendLine("微信号: ${friend.customWxId}")
                                    }
                                }
                                copyToClipboard(context, text)
                                showToast(context, "已复制")
                            }) { Text("复制") }
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
                            }) { Text("删除") }
                        }
                    }

                    else -> null
                }
            )
        }
    }
}
