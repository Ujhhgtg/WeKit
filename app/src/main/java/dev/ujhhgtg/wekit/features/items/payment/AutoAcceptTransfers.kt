package dev.ujhhgtg.wekit.features.items.payment

import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Feature(name = "自动接收转账", categories = ["红包与支付"], description = "监听消息并自动接收转账")
object AutoAcceptTransfers : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoAcceptTransfers"

    var selectedWorkflowId: String? by WePrefs.prefOption("auto_transfer_workflow_id", null as String?)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type != MessageType.TRANSFER.code) return

        WeLogger.i(TAG, "detected transfer message; type=$type")
        handleTransfer(values)
    }

    private val PAY_SUBTYPE_REGEX = Regex("<paysubtype.*?(\\d+)</paysubtype>")

    private fun parsePaySubtypeFromXml(xml: String): String? {
        return runCatching {
            PAY_SUBTYPE_REGEX
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }.getOrDefault(null)
    }

    private fun handleTransfer(values: ContentValues) {
        val workflowId = selectedWorkflowId ?: return
        val talker = values.getAsString("talker") ?: ""
        val content = values.getAsString("content") ?: return

        val subtype = parsePaySubtypeFromXml(content)
        if (subtype != "1") {
            WeLogger.w(TAG, "status=$subtype is not 1, ignoring")
            return
        }

        val msgInfo = MessageInfo.fromContentValues(values)
        val transferMsg = msgInfo.toTransferMessage() ?: run {
            WeLogger.w(TAG, "failed to parse transfer message")
            return
        }

        val payerUsername = transferMsg.payerUsername.ifEmpty { msgInfo.sender }.ifEmpty { msgInfo.talker }
        if (payerUsername == WeApi.selfWxId) { WeLogger.w(TAG, "self is payer, ignoring"); return }

        val receiverUsername = transferMsg.receiverUsername
        if (receiverUsername != WeApi.selfWxId) { WeLogger.w(TAG, "self is not receiver, ignoring"); return }

        val ctx = TriggerContext.newTransfer(
            sender = talker,
            amount = transferMsg.feedesc,
            currency = "CNY",
            note = transferMsg.des,
            msgSvrId = msgInfo.serverId,
        )

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch {
            val workflow = WorkflowRepository.getById(workflowId) ?: return@launch
            WorkflowEngine.execute(workflow, ctx)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val workflows by produceState<List<dev.ujhhgtg.wekit.workflow.model.Workflow>>(initialValue = emptyList()) {
                value = WorkflowRepository.getAll()
            }
            val boundName = remember(workflows, selectedWorkflowId) {
                workflows.firstOrNull { it.id == selectedWorkflowId }?.name ?: "未绑定"
            }

            AlertDialogContent(
                title = { Text("自动接收转账") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    val inner by produceState<List<dev.ujhhgtg.wekit.workflow.model.Workflow>>(initialValue = emptyList()) {
                                        value = WorkflowRepository.getAll()
                                    }
                                    AlertDialogContent(
                                        title = { Text("选择工作流") },
                                        text = {
                                            DefaultColumn {
                                                ListItem(
                                                    modifier = Modifier.clickable {
                                                        selectedWorkflowId = null; onDismiss()
                                                    },
                                                    headlineContent = { Text("不绑定") },
                                                )
                                                inner.forEach { wf ->
                                                    ListItem(
                                                        modifier = Modifier.clickable {
                                                            selectedWorkflowId = wf.id; onDismiss()
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
                            supportingContent = { Text(boundName) },
                            headlineContent = { Text("绑定工作流") },
                        )
                    }
                },
                confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
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
