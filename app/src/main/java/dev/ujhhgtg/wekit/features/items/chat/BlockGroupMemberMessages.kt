package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.CancelIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    name = "屏蔽群成员消息",
    categories = ["聊天"],
    description = "屏蔽指定群成员的所有消息；在对话列表长按群聊进入管理"
)
object BlockGroupMemberMessages : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BlockGroupMemberMessages::class)

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777030,
                text = "屏蔽成员管理",
                drawable = CancelIcon,
                shouldShow = { ctx, _ -> ctx.talker.contains("@chatroom") },
                onClick = { ctx -> showBlockMemberManager(ctx.activity, ctx.talker) }
            )
        )
    }

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
        WeChatMessageViewApi.addListener(object : WeChatMessageViewApi.ICreateViewListener {
            override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
                try {
                    val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param) ?: return
                    val groupId = msgInfo.talker
                    if (!groupId.contains("@chatroom")) return
                    val sender = msgInfo.sender
                    if (sender.isEmpty() || sender == "system") return
                    if (sender in getBlockedSet(groupId)) {
                        view.visibility = View.GONE
                        view.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                    }
                } catch (_: Exception) { }
            }
        })
    }

    override fun onDisable() { WeConversationContextMenuApi.removeProvider(this) }

    private fun getPrefKey(groupId: String) = "blocked_members_$groupId"
    private fun getBlockedSet(groupId: String): Set<String>
        = WePrefs.getStringSetOrDef(getPrefKey(groupId), emptySet())

    private fun saveBlockedSet(groupId: String, set: Set<String>) {
        WePrefs.putStringSet(getPrefKey(groupId), set)
    }

    private fun blockMembers(groupId: String, wxIds: Set<String>) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.addAll(wxIds)
        saveBlockedSet(groupId, set)
    }

    private fun unblockMember(groupId: String, wxId: String) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.remove(wxId)
        saveBlockedSet(groupId, set)
    }

    fun showBlockMemberManager(context: Context, groupId: String) {
        showComposeDialog(context) {
            var tab by remember { mutableIntStateOf(0) }
            val blockedSet = remember { mutableStateOf(getBlockedSet(groupId)) }
            val members = remember { mutableStateOf<List<WeContact>>(emptyList()) }
            var loaded by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val selectedToBlock = remember { mutableStateListOf<String>() }

            LaunchedEffect(Unit) {
                val m = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                members.value = m; loaded = true
            }

            val filteredMembers = remember(searchQuery, members.value, blockedSet.value) {
                members.value.filter { it.wxId !in blockedSet.value && (searchQuery.isBlank() ||
                    (it.displayName.ifEmpty { it.nickname }).lowercase().contains(searchQuery.lowercase()) ||
                    it.wxId.lowercase().contains(searchQuery.lowercase())) }
            }

            AlertDialogContent(
                title = { Text("屏蔽成员管理", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.size(360.dp, 480.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(onClick = { tab = 0 }) { Text("已屏蔽 (${blockedSet.value.size})", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                            TextButton(onClick = { tab = 1 }) { Text("添加屏蔽", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                        }
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))

                        when (tab) {
                            0 -> {
                                if (blockedSet.value.isEmpty()) {
                                    Text("暂无已屏蔽的成员", modifier = Modifier.padding(16.dp), color = ComposeColor.Gray)
                                } else {
                                    LazyColumn(Modifier.weight(1f)) {
                                        items(blockedSet.value.toList(), { it }) { wxId ->
                                            val contact = try { WeDatabaseApi.getFriend(wxId) } catch (_: Exception) { null }
                                            val name = contact?.displayName ?: contact?.nickname ?: wxId
                                            ListItem(
                                                headlineContent = { Text(name, fontSize = 14.sp) },
                                                supportingContent = { Text(wxId, fontSize = 11.sp, color = ComposeColor.Gray) },
                                                trailingContent = {
                                                    TextButton(onClick = {
                                                        unblockMember(groupId, wxId)
                                                        blockedSet.value = getBlockedSet(groupId)
                                                        context.showToast("已解除屏蔽")
                                                    }) { Text("解除", fontSize = 12.sp) }
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("提示：解除屏蔽后已隐藏的消息不会自动恢复", fontSize = 11.sp, color = ComposeColor.Gray)
                                }
                            }
                            1 -> {
                                if (!loaded) {
                                    Text("加载成员中...", modifier = Modifier.padding(16.dp))
                                } else if (members.value.isEmpty()) {
                                    Text("无法获取群成员列表", modifier = Modifier.padding(16.dp), color = ComposeColor.Gray)
                                } else {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = { Text("搜索成员") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("群成员 ${members.value.size} 人", fontSize = 11.sp, color = ComposeColor.Gray)
                                    if (selectedToBlock.isNotEmpty()) {
                                        Text("已选 ${selectedToBlock.size} 人", fontSize = 11.sp, color = ComposeColor(0xFF4CAF50))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    LazyColumn(Modifier.weight(1f)) {
                                        items(filteredMembers, { it.wxId }) { m ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    if (m.wxId in selectedToBlock) selectedToBlock.remove(m.wxId)
                                                    else selectedToBlock.add(m.wxId)
                                                }.padding(vertical = 6.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = m.wxId in selectedToBlock,
                                                    onCheckedChange = { c -> if (c) selectedToBlock.add(m.wxId) else selectedToBlock.remove(m.wxId) }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                AsyncImage(
                                                    model = m.avatarUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                                    imageLoader = GlobalImageLoader
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(m.displayName.ifEmpty { m.nickname }, fontSize = 14.sp)
                                                    Text(m.wxId, fontSize = 11.sp, color = ComposeColor.Gray)
                                                }
                                            }
                                        }
                                    }
                                    if (selectedToBlock.isNotEmpty()) {
                                        Button(onClick = {
                                            blockMembers(groupId, selectedToBlock.toSet())
                                            blockedSet.value = getBlockedSet(groupId)
                                            selectedToBlock.clear()
                                            tab = 0
                                            context.showToast("已屏蔽 ${selectedToBlock.size} 人")
                                        }, Modifier.fillMaxWidth()) { Text("屏蔽选中 (${selectedToBlock.size})", fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }
}