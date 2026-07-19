package dev.ujhhgtg.wekit.features.items.payment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.engine.TriggerContext
import dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine
import dev.ujhhgtg.wekit.workflow.model.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@SuppressLint("DiscouragedApi")
@Feature(name = "自动抢红包", categories = ["红包与支付"], description = "监听消息并自动拆开红包")
object AutoOpenRedPackets : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    IResolveDex {

    private const val TAG = "AutoOpenRedPackets"

    private val classReceiveLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneReceiveLuckyMoney")
                }
            }
        }
    }
    private val classOpenLuckyMoney by dexClass {
        matcher {
            methods {
                add {
                    name = "<init>"
                    usingEqStrings("MicroMsg.NetSceneOpenLuckyMoney")
                }
            }
        }
    }
    private val methodReceiveOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classReceiveLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }
    private val methodOpenOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classOpenLuckyMoney.clazz)
            name = "onGYNetEnd"
            paramCount = 3
        }
    }

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    var selectedWorkflowId: String? by WePrefs.prefOption("auto_red_packet_workflow_id", null as String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val headImg: String = "",
        val nickName: String = ""
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodReceiveOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter
            val sendId = json.optString("sendId")
            val timingIdentifier = json.optString("timingIdentifier")

            if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap[sendId] ?: run {
                WeLogger.e(TAG, "failed to find red packet in map (sendId=$sendId)")
                return@hookAfter
            }
            WeLogger.i(TAG, "unpack request finished, sending open request ($sendId)")

            thread(name = "OpenRedPacketThread") {
                try {
                    val openReq = classOpenLuckyMoney.clazz.createInstance(
                        info.msgType, info.channelId, info.sendId, info.nativeUrl,
                        info.headImg, info.nickName, info.talker,
                        "v1.0", timingIdentifier, ""
                    )
                    WeNetSceneApi.sendNetScene(openReq)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send open request", e)
                    currentRedPacketMap.remove(sendId)
                }
            }
        }

        methodOpenOnGYNetEnd.hookAfter {
            val json = args[2] as? JSONObject ?: return@hookAfter

            val sendId = json.optString("sendId")
            if (sendId.isNullOrEmpty()) return@hookAfter

            val info = currentRedPacketMap.remove(sendId) ?: return@hookAfter

            val retCode = json.optInt("retcode", -1)
            if (retCode != 0) {
                WeLogger.w(TAG, "failed to grab packet (retcode=$retCode, sendId=$sendId)")
                return@hookAfter
            }

            val receiveStatus = json.optInt("receiveStatus", -1)
            if (receiveStatus != 2) {
                WeLogger.w(TAG, "missed the packet (recvStatus=$receiveStatus, sendId=$sendId)")
                return@hookAfter
            }

            val amount = json.optInt("amount", 0)
            if (amount <= 0) return@hookAfter

            WeLogger.i(TAG, "grabbed packet ¥${amount / 100.0} from ${info.talker} (sendId=$sendId)")
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (MessageType.fromCode(type)?.isRedPacket ?: false) {
            WeLogger.i(TAG, "detected red packet message; type=$type")
            handleRedPacket(values)
        }
    }

    private fun handleRedPacket(values: ContentValues) {
        try {
            val msgInfo = MessageInfo.fromContentValues(values)

            val talker = msgInfo.talker
            val isGroup = msgInfo.isInGroupChat
            val sender = msgInfo.sender
            val msgSvrId = msgInfo.serverId

            val content = msgInfo.content
            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            currentRedPacketMap[sendId] = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                headImg = headImg,
                nickName = nickName
            )

            val senderDisplayName = if (isGroup) {
                WeDatabaseApi.getGroupMemberDisplayName(talker, sender)
            } else {
                WeDatabaseApi.getDisplayName(sender)
            }

            scope.launch {
                val workflowId = selectedWorkflowId ?: run {
                    WeLogger.d(TAG, "no workflow bound, skipping execution")
                    return@launch
                }
                val workflow = WorkflowRepository.getById(workflowId) ?: run {
                    WeLogger.w(TAG, "bound workflow $workflowId not found")
                    return@launch
                }
                val ctx = TriggerContext.newRedPacket(
                    sender = sender,
                    groupId = if (isGroup) talker else "",
                    senderName = senderDisplayName,
                    msgSvrId = msgSvrId,
                )
                WorkflowEngine.execute(workflow, ctx)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
        }
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        val pattern = "<$tag><!\\[CDATA\\[(.*?)]]></$tag>".toRegex()
        val match = pattern.find(xml)
        if (match != null) return match.groupValues[1]
        val patternSimple = "<$tag>(.*?)</$tag>".toRegex()
        val matchSimple = patternSimple.find(xml)
        return matchSimple?.groupValues?.get(1) ?: ""
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        currentRedPacketMap.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val workflows by produceState<List<Workflow>>(initialValue = emptyList()) {
                value = WorkflowRepository.getAll()
            }

            val boundName = remember(workflows, selectedWorkflowId) {
                workflows.firstOrNull { it.id == selectedWorkflowId }?.name ?: "未绑定"
            }

            AlertDialogContent(
                title = { Text("自动抢红包") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    val innerWorkflows by produceState<List<Workflow>>(initialValue = emptyList()) {
                                        value = WorkflowRepository.getAll()
                                    }
                                    AlertDialogContent(
                                        title = { Text("选择工作流") },
                                        text = {
                                            DefaultColumn {
                                                ListItem(
                                                    modifier = Modifier.clickable {
                                                        selectedWorkflowId = null
                                                        onDismiss()
                                                    },
                                                    headlineContent = { Text("不绑定") },
                                                )
                                                innerWorkflows.forEach { wf ->
                                                    ListItem(
                                                        modifier = Modifier.clickable {
                                                            selectedWorkflowId = wf.id
                                                            onDismiss()
                                                        },
                                                        headlineContent = { Text(wf.name) },
                                                        supportingContent = wf.description.takeIf { it.isNotBlank() }
                                                            ?.let { desc -> { Text(desc) } },
                                                    )
                                                }
                                            }
                                        },
                                        dismissButton = { TextButton(onDismiss) { Text("取消") } }
                                    )
                                }
                            },
                            headlineContent = { Text("绑定工作流") },
                            supportingContent = { Text(boundName) },
                        )
                    }
                },
                confirmButton = { Button(onClick = onDismiss) { Text("确定") } },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } }
                )
            }
            return false
        }

        return true
    }
}
