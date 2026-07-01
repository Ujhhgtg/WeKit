package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.CancelIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    name = "屏蔽群成员消息",
    categories = ["聊天"],
    description = "屏蔽指定群成员的所有消息；在对话列表长按群聊进入管理"
)
object BlockGroupMemberMessages : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BlockGroupMemberMessages::class)
    private var tempDurationMs by WePrefs.prefOption("block_member_temp_duration_ms", 3600000L)

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
                    val blockedSet = getBlockedSet(groupId)
                    if (sender !in blockedSet) return
                    view.visibility = View.GONE
                    view.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                } catch (_: Exception) { }
            }
        })
    }

    override fun onDisable() { WeConversationContextMenuApi.removeProvider(this) }

    private fun getPrefKey(groupId: String) = "blocked_members_$groupId"
    private fun getBlockedSet(groupId: String): Set<String>
        = WePrefs.getStringSetOrDef(getPrefKey(groupId), emptySet())

    fun isBlocked(groupId: String, wxId: String): Boolean = wxId in getBlockedSet(groupId)

    fun blockMember(groupId: String, wxId: String) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.add(wxId)
        WePrefs.putStringSet(getPrefKey(groupId), set)
        WeLogger.i(TAG, "blocked $wxId in $groupId")
    }

    fun unblockMember(groupId: String, wxId: String) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.remove(wxId)
        WePrefs.putStringSet(getPrefKey(groupId), set)
        WeLogger.i(TAG, "unblocked $wxId in $groupId")
    }

    data class BlockedMember(val wxId: String, val displayName: String)

    fun getBlockedMembersDetails(groupId: String): List<BlockedMember> {
        val blocked = getBlockedSet(groupId)
        return blocked.map { wxId ->
            val contact = try { WeDatabaseApi.getFriend(wxId) } catch (_: Exception) { null }
            BlockedMember(wxId, contact?.displayName ?: contact?.nickname ?: wxId)
        }
    }

    fun showBlockMemberManager(context: Context, groupId: String) {
        showComposeDialog(context) {
            val blockedMembers = remember { mutableStateListOf<BlockedMember>().apply { addAll(getBlockedMembersDetails(groupId)) } }
            var tab by remember { mutableIntStateOf(0) }
            var searchQuery by remember { mutableStateOf("") }
            val searchResults = remember { mutableStateListOf<WeContact>() }
            val selectedSearch = remember { mutableStateListOf<String>() }

            AlertDialogContent(
                title = { Text("屏蔽成员管理", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.size(340.dp, 440.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(onClick = { tab = 0 }) { Text("已屏蔽 (${blockedMembers.size})", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                            TextButton(onClick = { tab = 1 }) { Text("添加屏蔽", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                        }
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        when (tab) {
                            0 -> {
                                if (blockedMembers.isEmpty()) {
                                    Text("暂无已屏蔽的成员", modifier = Modifier.padding(16.dp), color = ComposeColor.Gray)
                                } else {
                                    LazyColumn(Modifier.weight(1f)) {
                                        items(blockedMembers, { it.wxId }) { bm ->
                                            ListItem(
                                                headlineContent = { Text(bm.displayName, fontSize = 14.sp) },
                                                supportingContent = { Text(bm.wxId, fontSize = 11.sp, color = ComposeColor.Gray) },
                                                trailingContent = {
                                                    TextButton(onClick = {
                                                        unblockMember(groupId, bm.wxId)
                                                        blockedMembers.remove(bm)
                                                        Toast.makeText(context, "已解除屏蔽", Toast.LENGTH_SHORT).show()
                                                    }) { Text("解除", fontSize = 12.sp) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            1 -> {
                                OutlinedTextField(value = searchQuery, onValueChange = { q ->
                                    searchQuery = q
                                    if (q.length >= 1) {
                                        val members = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                                        searchResults.clear()
                                        searchResults.addAll(members.filter { m ->
                                            (m.displayName.ifEmpty { m.nickname }).lowercase().contains(q.lowercase()) || m.wxId.lowercase().contains(q.lowercase())
                                        })
                                    } else searchResults.clear()
                                }, label = { Text("搜索昵称或 wxid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                if (searchResults.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                        TextButton(onClick = { selectedSearch.addAll(searchResults.map { it.wxId }) }) { Text("全选", fontSize = 12.sp) }
                                        TextButton(onClick = { selectedSearch.clear() }) { Text("清空", fontSize = 12.sp) }
                                    }
                                }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(searchResults, { it.wxId }) { member ->
                                        ListItem(
                                            headlineContent = { Text(member.displayName.ifEmpty { member.nickname }, fontSize = 14.sp) },
                                            supportingContent = { Text(member.wxId, fontSize = 11.sp, color = ComposeColor.Gray) },
                                            leadingContent = {
                                                Checkbox(checked = member.wxId in selectedSearch, onCheckedChange = { c -> if (c) selectedSearch.add(member.wxId) else selectedSearch.remove(member.wxId) })
                                            }
                                        )
                                    }
                                }
                                if (selectedSearch.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        var count = 0
                                        for (wxId in selectedSearch) {
                                            if (!isBlocked(groupId, wxId)) { blockMember(groupId, wxId); count++ }
                                        }
                                        selectedSearch.clear()
                                        blockedMembers.clear()
                                        blockedMembers.addAll(getBlockedMembersDetails(groupId))
                                        Toast.makeText(context, "已屏蔽 $count 人", Toast.LENGTH_SHORT).show()
                                    }, Modifier.fillMaxWidth()) { Text("屏蔽选中 (${selectedSearch.size})") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { blockedMembers.clear(); blockedMembers.addAll(getBlockedMembersDetails(groupId)) }) { Text("刷新") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }
}