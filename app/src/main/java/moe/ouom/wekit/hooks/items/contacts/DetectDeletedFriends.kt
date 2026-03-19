package moe.ouom.wekit.hooks.items.contacts

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.api.core.WeApi
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.net.WePacketHelper
import moe.ouom.wekit.hooks.api.net.WeProtoData
import moe.ouom.wekit.hooks.api.net.abc.IWePacketInterceptor
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.logging.WeLogger
import kotlin.time.Duration.Companion.seconds

@HookItem(path = "联系人与群组/检测单向删除好友", desc = "批量扫描全部好友, 检测是否被对方单向删除")
object DetectDeletedFriends : ClickableHookItem(), IWePacketInterceptor {

    override fun noSwitchWidget(): Boolean = true

    private val TAG = nameof(DetectDeletedFriends)

    private enum class AbnormalFriendStatus {
        Blocked,
        Deleted
    }

    private data class AbnormalFriend(
        val nickname: String,
        val remarkName: String,
        val wxId: String,
        val customWxId: String,
        val status: AbnormalFriendStatus
    )

    private sealed class DialogPhase {
        object Idle : DialogPhase()
        data class Scanning(var completed: MutableIntState, val total: Int) : DialogPhase()
        data class Done(val friends: List<AbnormalFriend>) : DialogPhase()
    }

    override fun onClick(context: Context) {
        val phaseState = mutableStateOf<DialogPhase>(DialogPhase.Idle)

        val selfWxId = WeApi.selfWxId
        val friends = WeDatabaseApi.getFriends().filter { c ->
            c.type != 2051 && c.type != 2049 && c.wxId.startsWith("wxid_") && c.wxId != selfWxId }

        showComposeDialog(context) {
            var phase by phaseState

            LaunchedEffect(phase) {
                if (phase is DialogPhase.Scanning) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val abnormalFriends = mutableListOf<AbnormalFriend>()
                        for (friend in friends) {
                            WePacketHelper.sendCgi("/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0,
                                """{"2":"${friend.customWxid}"}""") {
                                // status is always success
                                onSuccess { json, _ ->
                                    val jsonObj = Json.parseToJsonElement(json).jsonObject
                                    val realName = jsonObj["4"]
                                    WeLogger.d("DetectDeletedFriends", "realName=$realName")
                                    if (realName == null) {
                                        abnormalFriends += AbnormalFriend(
                                            nickname = friend.nickname,
                                            remarkName = friend.remarkName.ifBlank { "<无备注>" },
                                            wxId = friend.wxId,
                                            customWxId = friend.customWxid,
                                            // TODO: figure out status, might have to perform another request
                                            status = AbnormalFriendStatus.Deleted,
                                        )
                                    }
                                    (phase as DialogPhase.Scanning).completed.intValue++
                                }

                                onFail { errType, errCode, errMsg ->
                                    WeLogger.w(TAG, "failed friend ${friend.wxId}: $errType, $errCode, $errMsg")
                                    (phase as DialogPhase.Scanning).completed.intValue++
                                }
                            }
                            // seems like WeChat's server rate limits requests
                            delay(1.seconds)
                        }
                        phase = DialogPhase.Done(abnormalFriends)
                        dialog.setCancelable(true)
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LinearWavyProgressIndicator(progress = {
                                    completed.toFloat() / (phase as DialogPhase.Scanning).total
                                })
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("正在扫描, 请稍等...\n已完成: ")
                            }
                        }

                        is DialogPhase.Done -> {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            Text("扫描完成, 有 ${abnormalFriends.size} 个状态异常的好友")
                            LazyColumn {
                                items(abnormalFriends) { friend ->
                                    ListItem(
                                        headlineContent = { Text("${friend.nickname} (${friend.remarkName})") },
                                        supportingContent = {
                                            Column {
                                                Text("状态: ${if (friend.status == AbnormalFriendStatus.Blocked) "被拉黑" else "被删除"}")
                                                Text("微信 ID: ${friend.wxId}")
                                                Text("微信号: ${friend.customWxId}")
                                            }
                                        })
                                }
                            }
                        }
                    }
                },
                dismissButton = when (phase) {
                    is DialogPhase.Idle -> {
                        @Composable {
                            TextButton(dismiss) { Text("取消") }
                        }
                    }
                    is DialogPhase.Scanning -> null
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
                        { Button(dismiss) { Text("关闭") } }
                    }

                    else -> null
                }
            )
        }
    }

    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (uri == "/cgi-bin/mmpay-bin/beforetransfer") return null

        WeLogger.d("DetectDeletedFriends", "req: uri=$uri, cgiId=$cgiId, reqBytes.size=${reqBytes.size}")
        val packet = WeProtoData()
        packet.fromBytes(reqBytes)
        WeLogger.d("DetectDeletedFriends", "req_packet: json=${packet.toJsonObject()}")

        return null
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        val packet = WeProtoData()
        packet.fromBytes(respBytes)
        WeLogger.d("DetectDeletedFriends", "resp: uri=$uri, cgiId=$cgiId, respBytes.size=${respBytes.size}")
        WeLogger.d("DetectDeletedFriends", "resp_packet=${packet.toJsonObject()}")
        return null
    }
}