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
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.GroupRemoveIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Feature(
    name = "批量退出群聊",
    categories = ["首页右上角菜单"],
    description = "在首页右上角菜单添加「批量退出群聊」选项, 支持全选/反选/速率控制/三次确认"
)
object BatchQuitGroups : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BatchQuitGroups::class)
    private var quitInterval by WePrefs.prefOption("batch_quit_groups_interval", 1500)

    private sealed class Phase {
        data object Idle : Phase()
        data class Selecting(
            val groups: List<WeGroup>,
            val selected: MutableSet<String>,
            val interval: Int
        ) : Phase()
        data class Confirming(
            val targets: List<WeGroup>,
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
            WeHomeScreenPopupMenuApi.MenuItem(777021, "批量退出群聊", GroupRemoveIcon) {
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
                    val glist = remember { WeDatabaseApi.getGroups() }
                    val sel = remember { mutableStateListOf<String>() }
                    var interval by remember { mutableIntStateOf(quitInterval) }
                    AlertDialogContent(
                        title = { Text("批量退出群聊", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(Modifier.size(320.dp, 420.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("操作间隔: ${interval}ms", 13.sp)
                                    Slider(interval.toFloat(), { interval = it.toInt() }, 500f..5000f, 17, Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                    TextButton({ sel.clear(); sel.addAll(glist.map { it.wxId }) }) { Text("全选", 13.sp) }
                                    TextButton({ val cur = sel.toSet(); sel.clear(); sel.addAll(glist.map { it.wxId }.filter { it !in cur }) }) { Text("反选", 13.sp) }
                                    TextButton({ sel.clear() }) { Text("清空", 13.sp) }
                                }
                                HorizontalDivider()
                                Text("已选 ${sel.size}/${glist.size}", 12.sp, Color.Gray)
                                LazyColumn(Modifier.weight(1f)) {
                                    items(glist, { it.wxId }) { g ->
                                        ListItem(
                                            headlineContent = { Text(g.nickname.ifEmpty { g.wxId }, 14.sp) },
                                            supportingContent = { Text(g.wxId, 11.sp, Color.Gray) },
                                            leadingContent = {
                                                Checkbox(g.wxId in sel, { c -> if (c) sel.add(g.wxId) else sel.remove(g.wxId) }, colors = CheckboxDefaults.colors())
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {{
                            if (sel.isEmpty()) { Toast.makeText(ctx, "请至少选一个群聊", 0).show(); return@Button }
                            quitInterval = interval
                            phase.value = Phase.Confirming(glist.filter { it.wxId in sel }, 1)
                        }},
                        dismissButton = {{ TextButton(onDismiss) { Text("取消") } }}
                    )
                }
                is Phase.Confirming -> {
                    val msgs = listOf(
                        "⚠️ 第一次确认

即将批量退出 ${p.targets.size} 个群聊，退出后需要重新被邀请才能进入！",
                        "⚠️ 第二次确认

您确定吗？退出群聊后之前的聊天记录将无法访问。",
                        "⚠️ 最终确认

这是最后一次确认！
即将批量退出 ${p.targets.size} 个群聊，操作间隔 ${quitInterval}ms。
是否继续？"
                    )
                    val t = listOf("第一次确认", "第二次确认", "最终确认").getOrElse(p.step - 1) { "确认" }
                    AlertDialogContent(
                        title = { Text(t, fontWeight = FontWeight.Bold) },
                        text = { Text(msgs.getOrElse(p.step - 1) { "确认?" }) },
                        confirmButton = {{
                            if (p.step >= 3) {
                                val fails = mutableListOf<String>()
                                phase.value = Phase.Running(p.targets.size, 0, fails)
                                startExec(ctx, p.targets, quitInterval, fails)
                            } else {
                                phase.value = p.copy(step = p.step + 1)
                            }
                        }},
                        dismissButton = {{ TextButton({ phase.value = Phase.Idle }) { Text("取消") } }}
                    )
                }
                is Phase.Running -> {
                    AlertDialogContent(
                        title = { Text("正在退出群聊...") },
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
                        title = { Text("退出完成", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("总计: ${p.total} | 成功: ${p.success} | 失败: ${p.failed.size}")
                                p.failed.forEach { Text("  • $it", 12.sp) }
                            }
                        },
                        confirmButton = {{ Button(onDismiss) { Text("关闭") } }}
                    )
                }
            }
        }
    }

    private fun startExec(ctx: Context, targets: List<WeGroup>, intervalMs: Int, fails: MutableList<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            var done = 0
            for (t in targets) {
                try {
                    val body = """{"2":"${t.wxId}"}"""
                    var ok = false
                    WePacketHelper.sendCgi("/cgi-bin/micromsg-bin/quitchatroom", 343, 0, 0, body) {
                        onSuccess { _, _ -> ok = true }
                        onFailure { _, _, _ -> ok = false }
                    }
                    if (!ok) fails.add(t.nickname.ifEmpty { t.wxId })
                } catch (e: Exception) {
                    WeLogger.e(TAG, "quit group ${t.wxId} failed", e)
                    fails.add(t.nickname.ifEmpty { t.wxId })
                }
                done++
                phase.value = Phase.Running(targets.size, done, fails)
                delay(intervalMs.toLong())
            }
            phase.value = Phase.Finished(targets.size, targets.size - fails.size, fails.toList())
        }
    }
}
