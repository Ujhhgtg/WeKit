package moe.ouom.wekit.hooks.item.automation

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.api.WeDatabaseListener
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.ui.creator.dialog.hooks.BaseHooksSettingsDialog
import moe.ouom.wekit.util.WeProtoData
import moe.ouom.wekit.util.log.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "自动化/自动化引擎", desc = "点击管理自动化规则")
class AutomationRuleManager : BaseClickableFunctionHookItem(),
    WeDatabaseListener.DatabaseInsertListener,
    IWePkgInterceptor
{
    companion object {
        private const val TAG = "AutomationRuleManager"

        // type=1 plain text
        // type=3 picture
        // type=43 video
        // type=48 static location
        // type=49 external app share
        // type=50 video/audio-only call
        // type=419430449 cash transfer
        // type=436207665 red packet
        // type=1040187441 qq music
        // type=1090519089 file
        val rules = CopyOnWriteArrayList(
            listOf(
                AutomationRule(
                    id = 0,
                    name = "builtin_js",
                    script = EmbeddedBuiltinJs.SCRIPT,
                    enabled = true
                )
            )
        )
    }

    // --- ui ---
    override fun onClick(context: Context?) {
        if (context == null) return
        showComposeDialog(context) { onDismiss ->
            BaseHooksSettingsDialog("管理规则", onDismiss) {
                AutomationSettingsDialogContent(rules)
            }
        }
    }

    override fun entry(classLoader: ClassLoader) {
        WeLogger.i(TAG, "registering automation DB listener")
        WeDatabaseListener.addListener(this)
    }

    // --- onMessage ---
    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (!OnMessage.enabled) {
            WeLogger.i(TAG, "OnMessage hook is disabled, ignoring")
            return
        }

        if (table != "message") return

        val isSend  = values.getAsInteger("isSend")  ?: return
        if (isSend != 0) return // ignore outgoing

        val talker  = values.getAsString("talker")   ?: return
        val content = values.getAsString("content")  ?: return
        val type    = values.getAsInteger("type")    ?: 0

        WeLogger.i(TAG, "message received: talker=$talker type=$type content.length=${content.length}")


        AutomationEngine.executeAllOnMessage(rules, talker, content, type, isSend)
    }

    override fun unload(classLoader: ClassLoader) {
        WeLogger.i(TAG, "removing automation DB listener")
        WeDatabaseListener.removeListener(this)
        super.unload(classLoader)
    }

    // --- onRequest ---
    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnRequest.enabled) {
            WeLogger.i(TAG, "OnRequest hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            val json = data.toJSON()
            val modifiedJson = AutomationEngine.executeAllOnRequest(uri, cgiId, json)
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }

        return null
    }

    // --- onResponse ---
    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnResponse.enabled) {
            WeLogger.i(TAG, "OnResponse hook is disabled, ignoring")
            return null
        }

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJSON()
            val modifiedJson = AutomationEngine.executeAllOnResponse(uri, cgiId, json)
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }
        return null
    }
}

@Composable
private fun AutomationSettingsDialogContent(rules: MutableList<AutomationRule>) {
    var snapshot by remember { mutableStateOf(rules.toList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() { snapshot = rules.toList() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("规则列表 (${snapshot.size})", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddDialog = true }) { Text("+ 添加") }
        }

        Spacer(Modifier.height(8.dp))

        if (snapshot.isEmpty()) {
            Text(
                "暂无规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                snapshot.forEach { rule ->
                    AutomationRuleCard(
                        rule = rule,
                        onToggle = {
                            val idx = rules.indexOfFirst { it.id == rule.id }
                            if (idx != -1) { rules[idx] = rule.copy(enabled = !rule.enabled) }
                            refresh()
                        },
                        onDelete = {
                            rules.removeAll { it.id == rule.id }
                            refresh()
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddAutomationRuleDialog(
            onConfirm = { newRule ->
                rules.add(newRule)
                refresh()
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun AutomationRuleCard(rule: AutomationRule, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "脚本长度: ${rule.script.length} 字符",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddAutomationRuleDialog(onConfirm: (AutomationRule) -> Unit, onDismiss: () -> Unit) {
    var ruleName by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自动化规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("规则名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("JavaScript 脚本") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = {
                        Text(
                            "function onMessage(talker, content, type, isSend) {\n" +
                                    "  // your code here\n" +
                                    "  return null;\n" +
                                    "}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ruleName.isBlank() || script.isBlank()) return@TextButton
                    onConfirm(
                        AutomationRule(
                            id = System.currentTimeMillis(),
                            name = ruleName,
                            script = script,
                            enabled = true
                        )
                    )
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}