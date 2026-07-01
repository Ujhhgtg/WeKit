package dev.ujhhgtg.wekit.features.items.home_screen_menu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
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
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Feature(
    name = "批量退出群聊",
    categories = ["首页右上角菜单"],
    description = "批量退出群聊，支持全选/反选/速率控制/三次确认"
)
object BatchQuitGroups : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BatchQuitGroups::class)
    private var quitInterval by WePrefs.prefOption("batch_quit_groups_interval", 1500)

    override fun onEnable() { WeHomeScreenPopupMenuApi.addProvider(this) }
    override fun onDisable() { WeHomeScreenPopupMenuApi.removeProvider(this) }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(777021, "批量退出群聊", GroupRemoveIcon) {
                val ctx = getTopMostActivity() ?: return@MenuItem
                showComposeDialog(ctx) {
                    val groups = remember { WeDatabaseApi.getGroups() }
                    val sel = remember { mutableStateListOf<String>() }
                    var interval by remember { mutableIntStateOf(quitInterval) }
                    var phase by remember { mutableIntStateOf(0) }

                    AlertDialogContent(
                        title = { Text("批量退出群聊", fontWeight = FontWeight.Bold) },
                        text = {
                            when (phase) {
                                0 -> {
                                    Column(Modifier.size(340.dp, 440.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("操作间隔: ${interval}ms", fontSize = 13.sp)
                                            Slider(value = interval.toFloat(), onValueChange = { interval = it.toInt() }, valueRange = 500f..5000f, steps = 17, modifier = Modifier.weight(1f))
                                        }
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                            TextButton(onClick = { sel.clear(); sel.addAll(groups.map { it.wxId }) }) { Text("全选", fontSize = 13.sp) }
                                            TextButton(onClick = { val s = sel.toSet(); sel.clear(); sel.addAll(groups.filter { it.wxId !in s }.map { it.wxId }) }) { Text("反选", fontSize = 13.sp) }
                                            TextButton(onClick = { sel.clear() }) { Text("清空", fontSize = 13.sp) }
                                        }
                                        HorizontalDivider()
                                        Text("已选 ${sel.size}/${groups.size}", fontSize = 12.sp, color = Color.Gray)
                                        LazyColumn(Modifier.weight(1f)) {
                                            items(groups, { it.wxId }) { g ->
                                                ListItem(
                                                    headlineContent = { Text(g.nickname.ifEmpty { g.wxId }, fontSize = 14.sp) },
                                                    supportingContent = { Text(g.wxId, fontSize = 11.sp, color = Color.Gray) },
                                                    leadingContent = {
                                                        Checkbox(checked = g.wxId in sel,
                                                            onCheckedChange = { c -> if (c) sel.add(g.wxId) else sel.remove(g.wxId) },
                                                            colors = CheckboxDefaults.colors()
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        if (sel.isNotEmpty()) {
                                            Button(onClick = {
                                                quitInterval = interval
                                                phase = 1
                                            }, Modifier.fillMaxWidth()) { Text("下一步 (${sel.size})", fontSize = 13.sp) }
                                        }
                                    }
                                }
                                1 -> {
                                    val msgs = listOf(
                                        "⚠️ 第一确认：即将退出 ${sel.size} 个群聊",
                                        "⚠️ 第二确认：操作不可逆！退出后需重新邀请",
                                        "⚠️ 最终确认：即将执行"
                                    )
                                    var confirmStep by remember { mutableIntStateOf(1) }
                                    Column {
                                        Text(msgs.getOrElse(confirmStep - 1) { "" })
                                        Spacer(Modifier.height(12.dp))
                                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                            Button(onClick = {
                                                if (confirmStep >= 3) {
                                                    phase = 2
                                                    startQuit(ctx, groups.filter { it.wxId in sel }, quitInterval, sel.toList())
                                                } else { confirmStep++ }
                                            }) { Text("确认 (${confirmStep}/3)", fontSize = 13.sp) }
                                            TextButton(onClick = { phase = 0 }) { Text("取消", fontSize = 13.sp) }
                                        }
                                    }
                                }
                                2 -> {
                                    Column {
                                        Text("执行中...")
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                3 -> {
                                    Column {
                                        Text("退出完成！")
                                        Text("总计: ${sel.size}")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                                }
                                else -> { Text("未知状态") }
                            }
                        }
                    )
                }
            }
        )
    }

    private fun startQuit(ctx: Context, targets: List<WeGroup>, intervalMs: Int, selList: List<String>) {
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
                    if (!ok) { /* retry later */ }
                } catch (e: Exception) {
                    WeLogger.e(TAG, "quit ${t.wxId} failed", e)
                }
                done++
                delay(intervalMs.toLong())
            }
        }
    }
}