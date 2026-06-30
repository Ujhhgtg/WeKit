package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    name = "屏蔽群成员消息",
    categories = ["聊天"],
    description = "屏蔽指定群成员的所有消息（文字/图片/红包/接龙等），连按两次头像快速屏蔽"
)
object BlockGroupMemberMessages : SwitchFeature() {
    private val TAG = nameOf(BlockGroupMemberMessages::class)

    private var tempDurationMs by WePrefs.prefOption("block_member_temp_duration_ms", 3600000L)

    override fun onEnable() {
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

                    if (isTemporarilyBlocked(groupId, sender)) {
                        val expiry = getBlockExpiry(groupId, sender)
                        if (expiry > 0 && System.currentTimeMillis() > expiry) {
                            unblockMember(groupId, sender)
                            return
                        }
                    }

                    view.visibility = View.GONE
                    view.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                } catch (e: Exception) {
                    WeLogger.w(TAG, "onCreateView hook failed", e)
                }
            }
        })
    }

    override fun onDisable() {
        WeLogger.i(TAG, "disabled")
    }

    private fun getPrefKey(groupId: String) = "blocked_members_$groupId"
    private fun getExpiryPrefKey(groupId: String) = "blocked_members_expiry_$groupId"

    fun getBlockedSet(groupId: String): Set<String> {
        return WePrefs.getStringSetOrDef(getPrefKey(groupId), emptySet())
    }

    fun isBlocked(groupId: String, wxId: String): Boolean {
        val set = getBlockedSet(groupId)
        if (wxId !in set) return false
        if (isTemporarilyBlocked(groupId, wxId)) {
            val expiry = getBlockExpiry(groupId, wxId)
            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                unblockMember(groupId, wxId)
                return false
            }
        }
        return true
    }

    private fun getBlockExpiry(groupId: String, wxId: String): Long {
        val raw = WePrefs.getStringSetOrDef(getExpiryPrefKey(groupId), emptySet())
        for (entry in raw) {
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[0] == wxId) {
                return parts[1].toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    private fun isTemporarilyBlocked(groupId: String, wxId: String): Boolean {
        return getBlockExpiry(groupId, wxId) > 0L
    }

    fun blockMember(groupId: String, wxId: String, isTemp: Boolean) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.add(wxId)
        WePrefs.putStringSet(getPrefKey(groupId), set)
        if (isTemp) {
            val expiry = System.currentTimeMillis() + tempDurationMs
            setBlockExpiry(groupId, wxId, expiry)
        }
        WeLogger.i(TAG, "blocked $wxId in $groupId${if (isTemp) " (temporary)" else ""}")
    }

    private fun setBlockExpiry(groupId: String, wxId: String, expiry: Long) {
        val raw = WePrefs.getStringSetOrDef(getExpiryPrefKey(groupId), emptySet()).toMutableSet()
        raw.removeAll { it.startsWith("$wxId|") }
        raw.add("$wxId|$expiry")
        WePrefs.putStringSet(getExpiryPrefKey(groupId), raw)
    }

    fun unblockMember(groupId: String, wxId: String) {
        val set = getBlockedSet(groupId).toMutableSet()
        set.remove(wxId)
        WePrefs.putStringSet(getPrefKey(groupId), set)

        val expirySet = WePrefs.getStringSetOrDef(getExpiryPrefKey(groupId), emptySet()).toMutableSet()
        expirySet.removeAll { it.startsWith("$wxId|") }
        WePrefs.putStringSet(getExpiryPrefKey(groupId), expirySet)
        WeLogger.i(TAG, "unblocked $wxId in $groupId")
    }

    fun blockMemberPermanent(groupId: String, wxId: String) {
        blockMember(groupId, wxId, false)
    }

    fun blockMemberTemporary(groupId: String, wxId: String) {
        blockMember(groupId, wxId, true)
    }

    fun getBlockedMembersDetails(groupId: String): List<BlockedMember> {
        val blocked = getBlockedSet(groupId)
        if (blocked.isEmpty()) return emptyList()
        return blocked.map { wxId ->
            val expiry = getBlockExpiry(groupId, wxId)
            val isTemp = expiry > 0L
            val expiresIn = if (isTemp) (expiry - System.currentTimeMillis()).coerceAtLeast(0) else 0L
            val contact = try { WeDatabaseApi.getFriend(wxId) } catch (_: Exception) { null }
            BlockedMember(
                wxId = wxId,
                displayName = contact?.displayName ?: contact?.nickname ?: wxId,
                isTemp = isTemp,
                expiresInMs = expiresIn
            )
        }
    }

    data class BlockedMember(
        val wxId: String,
        val displayName: String,
        val isTemp: Boolean,
        val expiresInMs: Long
    )

    fun showBlockMemberManager(context: Context, groupId: String) {
        showComposeDialog(context) {
            var tab by remember { mutableIntStateOf(0) }
            var searchQuery by remember { mutableStateOf("") }
            val blockedMembers = remember { mutableStateListOf<BlockedMember>() }
            var loaded by remember { mutableStateOf(false) }

            if (!loaded) {
                loaded = true
                blockedMembers.clear()
                blockedMembers.addAll(getBlockedMembersDetails(groupId))
            }

            val searchResults = remember { mutableStateListOf<WeContact>() }
            val selectedSearch = remember { mutableStateListOf<String>() }
            var searchMode by remember { mutableIntStateOf(0) }

            AlertDialogContent(
                title = { Text("群成员消息屏蔽", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.size(340.dp, 440.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(
                                { tab = 0 }
                            ) { Text("已屏蔽 (${blockedMembers.size})", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                            TextButton(
                                { tab = 1 }
                            ) { Text("批量屏蔽", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                        }
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        when (tab) {
                            0 -> {
                                if (blockedMembers.isEmpty()) {
                                    Text("暂无已屏蔽的成员", fontSize = 14.sp, color = ComposeColor.Gray, modifier = Modifier.padding(16.dp))
                                } else {
                                    LazyColumn(Modifier.weight(1f)) {
                                        items(blockedMembers, { it.wxId }) { bm ->
                                            val timeLeft = if (bm.isTemp) {
                                                val mins = bm.expiresInMs / 60000
                                                if (mins > 0) "剩余 ${mins}分钟" else "即将过期"
                                            } else "永久"
                                            ListItem(
                                                headlineContent = { Text(bm.displayName, fontSize = 14.sp) },
                                                supportingContent = { Text("${if (bm.isTemp) "临时" else "永久"} | $timeLeft", fontSize = 12.sp, color = ComposeColor.Gray) },
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
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { q ->
                                        searchQuery = q
                                        if (q.length >= 1) {
                                            val members = try {
                                                WeDatabaseApi.getGroupMembers(groupId)
                                            } catch (_: Exception) { emptyList() }
                                            searchResults.clear()
                                            searchResults.addAll(members.filter { m ->
                                                val display = m.displayName.ifEmpty { m.nickname }
                                                q.lowercase() in (display.lowercase() + m.wxId.lowercase())
                                            })
                                        } else {
                                            searchResults.clear()
                                        }
                                    },
                                    label = { Text("搜索昵称或 wxid") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(searchMode == 0, { searchMode = 0 })
                                    Text("永久", fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    RadioButton(searchMode == 1, { searchMode = 1 })
                                    Text("临时 (${tempDurationMs / 60000}分钟)", fontSize = 13.sp)
                                }
                                if (searchResults.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                        TextButton({ selectedSearch.addAll(searchResults.map { it.wxId }) }) { Text("全选", fontSize = 12.sp) }
                                        TextButton({ selectedSearch.clear() }) { Text("清空", fontSize = 12.sp) }
                                    }
                                }
                                LazyColumn(Modifier.weight(1f)) {
                                    items(searchResults, { it.wxId }) { member ->
                                        ListItem(
                                            headlineContent = { Text(member.displayName.ifEmpty { member.nickname }, fontSize = 14.sp) },
                                            supportingContent = { Text(member.wxId, fontSize = 11.sp, color = ComposeColor.Gray) },
                                            leadingContent = {
                                                Checkbox(
                                                    member.wxId in selectedSearch,
                                                    { checked -> if (checked) selectedSearch.add(member.wxId) else selectedSearch.remove(member.wxId) },
                                                    colors = CheckboxDefaults.colors()
                                                )
                                            }
                                        )
                                    }
                                }
                                if (selectedSearch.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        val isTemp = searchMode == 1
                                        var count = 0
                                        for (wxId in selectedSearch) {
                                            if (!isBlocked(groupId, wxId)) {
                                                blockMember(groupId, wxId, isTemp)
                                                count++
                                            }
                                        }
                                        selectedSearch.clear()
                                        blockedMembers.clear()
                                        blockedMembers.addAll(getBlockedMembersDetails(groupId))
                                        Toast.makeText(context, "已屏蔽 $count 人", Toast.LENGTH_SHORT).show()
                                    }, Modifier.fillMaxWidth()) {
                                        Text("屏蔽选中 (${selectedSearch.size})")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    blockedMembers.clear()
                    blockedMembers.addAll(getBlockedMembersDetails(groupId))
                }) { Text("刷新") } },
                dismissButton = { TextButton(onClick = { /* dismiss handled by dialog */ }) { Text("关闭") } }
            )
        }
    }
}
