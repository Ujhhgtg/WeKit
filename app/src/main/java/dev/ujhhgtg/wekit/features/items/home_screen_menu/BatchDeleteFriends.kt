package dev.ujhhgtg.wekit.features.items.home_screen_menu

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.PersonRemoveIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Feature(
    name = "批量删除好友",
    categories = ["首页右上角菜单"],
    description = "在首页右上角菜单添加「批量删除好友」选项, 支持全选/反选/速率控制/三次确认"
)
object BatchDeleteFriends : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BatchDeleteFriends::class)
    private var deleteMode by WePrefs.prefOption("batch_delete_friends_mode", 0)
    private var deleteInterval by WePrefs.prefOption("batch_delete_friends_interval", 1000)

    private sealed class Phase {
        data object Idle : Phase()
        data class Selecting(
            val friends: List<WeContact>,
            val selected: MutableSet<String>,
            val mode: Int,
            val interval: Int
        ) : Phase()
        data class Confirming(
            val targets: List<WeContact>,
            val mode: Int,
            val step: Int
        ) : Phase()
        data class Running(
            val total: Int,
            val done: Int,
            val failed: MutableList<String>
        ) : Phase()
        data class Finished(val total: Int, val success: Int, val failed: List<String>) : Phase()
    }

    private val phase = mutableStateOf<Phase>(Phase.Idle)

    override fun onEnable() { WeHomeScreenPopupMenuApi.addProvider(this) }
    override fun onDisable() { WeHomeScreenPopupMenuApi.removeProvider(this) }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(777020, "批量删除好友", PersonRemoveIcon) {
                val ctx = try { (param.thisObject as? android.app.Activity) ?: return@MenuItem } catch (_: Exception) { return@MenuItem }
                showUI(ctx)
            }
        )
    }

    private fun showUI(ctx: Context) {
        phase.value = Phase.Idle
        showComposeDialog(ctx) {
            val p by remember { phase }
            when (p) {
                is Phase.Idle -> {
                    val flist = remember {
                        WeDatabaseApi.getFriends().filter { c ->
                            c.wxId != WeApi.selfWxId && c.type != 2051 && c.type != 2049
                        }
                    }
                    val sel = remember { mutableStateListOf<String>() }
                    var mode by remember { mutableIntStateOf(deleteMode) }
                    var interval by remember { mutableIntStateOf(deleteInterval) }
                    AlertDialogContent(
                        title = { Text("批量删除好友", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(Modifier.size(320.dp, 420.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(mode == 0) { mode = 0 }
                                    Text("仅删除", fontSize = 13.sp)
                                    Spacer(Modifier.width(12.dp))
                                    RadioButton(mode == 1) { mode = 1 }
                                    Text("拉黑+删除", fontSize = 13.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("间隔: ${interval}ms", fontSize = 13.sp)
                                    Slider(interval.toFloat(), { interval = it.toInt() }, 200f..5000f, 23, Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                    TextButton({ sel.clear(); sel.addAll(flist.map { it.wxId }) }) { Text("全选", fontSize = 13.sp) }
                                    TextButton({ val cur = sel.toSet(); sel.clear(); sel.addAll(flist.map { it.wxId }.filter { it !in cur }) }) { Text("反选", fontSize = 13.sp) }
                                    TextButton({ sel.clear() }) { Text("清空", fontSize = 13.sp) }
                                }
                                HorizontalDivider()
                                Text("已选 ${sel.size}/${flist.size}", fontSize = 12.sp, color = Color.Gray)
                                LazyColumn(Modifier.weight(1f)) {
                                    items(flist, { it.wxId }) { f ->
                                        ListItem(
                                            headlineContent = { Text(f.displayName.ifEmpty { f.nickname }, 14.sp) },
                                            supportingContent = { Text(f.wxId, 11.sp, Color.Gray) },
                                            leadingContent = {
                                                Checkbox(f.wxId in sel, { c -> if (c) sel.add(f.wxId) else sel.remove(f.wxId) }, colors = CheckboxDefaults.colors())
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = { Button(onClick = {
                            if (sel.isEmpty()) { Toast.makeText(ctx, "请至少选一个好友", 0).show(); return@onClick }
                            deleteMode = mode; deleteInterval = interval
                            phase.value = Phase.Confirming(flist.filter { it.wxId in sel }, mode, 1)
                        }) { Text("下一步") } },
                        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
                    )
                }
                is Phase.Selecting -> {
                    // Show selecting UI handled by Phase.Idle
                    Text("选择好友中...")
                }
                is Phase.Confirming -> {
                    val msgs = listOf(
                        "⚠️ 第一次确认

即将批量删除 ${p.targets.size} 个好友，此操作不可逆！请确认已备份重要聊天记录。",
                        "⚠️ 第二次确认

确认执行吗？删除后聊天记录将被清除，需对方重新添加。
当前模式：${if (p.mode == 0) "仅删除" else "拉黑+删除"}",
                        "⚠️ 最终确认

这是最后一次确认！
即将批量处理 ${p.targets.size} 个好友，操作间隔 ${deleteInterval}ms。
执行后将无法撤销，是否继续？"
                    )
                    val t = listOf("第一次确认", "第二次确认", "最终确认").getOrElse(p.step - 1) { "确认" }
                    AlertDialogContent(
                        title = { Text(t, fontWeight = FontWeight.Bold) },
                        text = { Text(msgs.getOrElse(p.step - 1) { "确认?" }) },
                        confirmButton = { Button(onClick = {
                            if (p.step >= 3) {
                                val fails = mutableListOf<String>()
                                phase.value = Phase.Running(p.targets.size, 0, fails)
                                startExec(ctx, p.targets, p.mode, deleteInterval, fails)
                            } else {
                                phase.value = p.copy(step = p.step + 1)
                            }
                        }) { Text("确认") } },
                        dismissButton = { TextButton(onClick = { phase.value = Phase.Idle }) { Text("取消") } }
                    )
                }
                is Phase.Running -> {
                    AlertDialogContent(
                        title = { Text("正在删除好友...") },
                        text = {
                            Column {
                                Text("进度: ${p.done}/${p.total}")
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator({ if (p.total > 0) p.done.toFloat() / p.total else 0f }, Modifier.fillMaxWidth())
                                if (p.failed.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("失败: ${p.failed.size} 个", Color.Red, 12.sp)
                                }
                            }
                        },
                        confirmButton = null, dismissButton = null
                    )
                }
                is Phase.Finished -> {
                    AlertDialogContent(
                        title = { Text("删除完成", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("总计: ${p.total} | 成功: ${p.success} | 失败: ${p.failed.size}")
                                p.failed.forEach { Text("  • $it", fontSize = 12.sp) }
                            }
                        },
                        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } }
                    )
                }
            }
        }
    }

    private fun startExec(ctx: Context, targets: List<WeContact>, mode: Int, intervalMs: Int, fails: MutableList<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            var done = 0
            for (t in targets) {
                try {
                    val body = if (mode == 0) """{"2":"${t.wxId}","4":1}""" else """{"2":"${t.wxId}","4":3}"""
                    var ok = false
                    WePacketHelper.sendCgi("/cgi-bin/micromsg-bin/deletecontact", 376, 0, 0, body) {
                        onSuccess { _, _ -> ok = true }
                        onFailure { _, _, _ -> ok = false }
                    }
                    if (!ok) fails.add(t.displayName.ifEmpty { t.wxId })
                } catch (e: Exception) {
                    WeLogger.e(TAG, "delete ${t.wxId} failed", e)
                    fails.add(t.displayName.ifEmpty { t.wxId })
                }
                done++
                phase.value = Phase.Running(targets.size, done, fails)
                delay(intervalMs.toLong())
            }
            phase.value = Phase.Finished(targets.size, targets.size - fails.size, fails.toList())
        }
    }
}
