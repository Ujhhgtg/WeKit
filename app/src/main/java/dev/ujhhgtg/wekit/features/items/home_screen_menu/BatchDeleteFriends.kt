package dev.ujhhgtg.wekit.features.items.home_screen_menu

import android.content.Context
import android.widget.Toast
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
    description = "批量删除好友，支持全选/反选/速率控制/三次确认"
)
object BatchDeleteFriends : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BatchDeleteFriends::class)
    private var deleteMode by WePrefs.prefOption("batch_delete_friends_mode", 0)
    private var deleteInterval by WePrefs.prefOption("batch_delete_friends_interval", 1000)

    override fun onEnable() { WeHomeScreenPopupMenuApi.addProvider(this) }
    override fun onDisable() { WeHomeScreenPopupMenuApi.removeProvider(this) }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(777020, "批量删除好友", PersonRemoveIcon) {
                val ctx = try { (param.thisObject as? android.app.Activity) ?: return@MenuItem } catch (_: Exception) { return@MenuItem }
                showComposeDialog(ctx) {
                    val friends = remember { WeDatabaseApi.getFriends() }
                    val sel = remember { mutableStateListOf<String>() }
                    var mode by remember { mutableIntStateOf(deleteMode) }
                    var interval by remember { mutableIntStateOf(deleteInterval) }
                    var phase by remember { mutableIntStateOf(0) }

                    AlertDialogContent(
                        title = { Text("批量删除好友", fontWeight = FontWeight.Bold) },
                        text = {
                            when (phase) {
                                0 -> {
                                    Column(Modifier.size(340.dp, 440.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = mode == 0, onClick = { mode = 0 })
                                            Text("仅删除", fontSize = 13.sp)
                                            Spacer(Modifier.width(12.dp))
                                            RadioButton(selected = mode == 1, onClick = { mode = 1 })
                                            Text("拉黑+删除", fontSize = 13.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("间隔: ${interval}ms", fontSize = 13.sp)
                                            Slider(value = interval.toFloat(), onValueChange = { interval = it.toInt() }, valueRange = 200f..5000f, steps = 23, modifier = Modifier.weight(1f))
                                        }
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                            TextButton(onClick = { sel.clear(); sel.addAll(friends.map { it.wxId }) }) { Text("全选", fontSize = 13.sp) }
                                            TextButton(onClick = { val s = sel.toSet(); sel.clear(); sel.addAll(friends.filter { it.wxId !in s }.map { it.wxId }) }) { Text("反选", fontSize = 13.sp) }
                                            TextButton(onClick = { sel.clear() }) { Text("清空", fontSize = 13.sp) }
                                        }
                                        HorizontalDivider()
                                        Text("已选 ${sel.size}/${friends.size}", fontSize = 12.sp, color = Color.Gray)
                                        LazyColumn(Modifier.weight(1f)) {
                                            items(friends, { it.wxId }) { f ->
                                                ListItem(
                                                    headlineContent = { Text(f.displayName.ifEmpty { f.nickname }, fontSize = 14.sp) },
                                                    supportingContent = { Text(f.wxId, fontSize = 11.sp, color = Color.Gray) },
                                                    leadingContent = {
                                                        Checkbox(checked = f.wxId in sel,
                                                            onCheckedChange = { c -> if (c) sel.add(f.wxId) else sel.remove(f.wxId) },
                                                            colors = CheckboxDefaults.colors()
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        if (sel.isNotEmpty()) {
                                            Button(onClick = {
                                                deleteMode = mode; deleteInterval = interval
                                                phase = 1
                                            }) { Text("下一步", fontSize = 13.sp) }
                                        }
                                    }
                                }
                                1 -> {
                                    val msgs = listOf(
                                        "第一次确认：即将删除 ${sel.size} 位好友",
                                        "第二次确认：操作不可逆！",
                                        "最终确认：即将执行"
                                    )
                                    var confirmStep by remember { mutableIntStateOf(1) }
                                    Column {
                                        Text(msgs.getOrElse(confirmStep - 1) { "" })
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                            Button(onClick = {
                                                if (confirmStep >= 3) {
                                                    phase = 2
                                                    val fails = mutableListOf<String>()
                                                    startDeletion(ctx, friends.filter { it.wxId in sel }, deleteMode, deleteInterval, fails)
                                                } else { confirmStep++ }
                                            }) { Text("确认", fontSize = 13.sp) }
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
                                        Text("删除完成！")
                                        Text("总计: ${sel.size}")
                                    }
                                    Button(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                                }
                                else -> {
                                    Text("状态错误")
                                }
                            }
                        }
                    )
                }
            }
        )
    }

    private fun startDeletion(ctx: Context, targets: List<WeContact>, mode: Int, intervalMs: Int, fails: MutableList<String>) {
        CoroutineScope(Dispatchers.IO).launch {
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
                delay(intervalMs.toLong())
            }
        }
    }
}