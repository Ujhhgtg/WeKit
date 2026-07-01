package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
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
    name = "群聊屏蔽功能",
    categories = ["聊天"],
    description = "屏蔽群成员消息和屏蔽关键词自动屏蔽；在对话列表长按群聊进入管理"
)
object BlockGroupMemberMessages : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BlockGroupMemberMessages::class)

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777030,
                text = "群聊屏蔽功能",
                drawable = CancelIcon,
                shouldShow = { ctx, _ -> ctx.talker.contains("@chatroom") },
                onClick = { ctx -> showBlockManager(ctx.activity, ctx.talker) }
            )
        )
    }

    private val hiddenSendersCache = mutableMapOf<String, MutableMap<String, MutableList<View>>>()

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

                    // 1. 手动屏蔽
                    if (sender in getBlockedSet(groupId)) {
                        hideView(view, groupId, sender)
                        return
                    }

                    // 2. 关键词自动屏蔽
                    if (getKeywordBlockEnabled(groupId)) {
                        val keywords = getKeywords(groupId)
                        val targets = getKeywordTargets(groupId)
                        if (keywords.isNotEmpty() && (targets.isEmpty() || sender in targets)) {
                            // 从多个来源提取消息文本，确保能匹配到
                            val rawContent = msgInfo.content
                            val actualContent = msgInfo.actualContent
                            // 尝试去掉非wxid_前缀: 对于 "customid:消息" 或 "customid:\n消息" 格式
                            val strippedContent = actualContent.substringAfter(":").substringAfter("\n").trim()

                            // 收集所有可能的文本来源
                            val textsToCheck = listOfNotNull(
                                actualContent.takeIf { it.isNotBlank() },
                                rawContent.takeIf { it.isNotBlank() },
                                strippedContent.takeIf { it.isNotBlank() }
                            ).distinct()

                            WeLogger.i(TAG, "keyword check: sender=$sender texts=$textsToCheck keywords=$keywords")

                            for (text in textsToCheck) {
                                for (kw in keywords) {
                                    if (text.contains(kw, ignoreCase = true)) {
                                        WeLogger.i(TAG, "auto-block $sender keyword='$kw' matched in '$text'")
                                        val set = getBlockedSet(groupId).toMutableSet()
                                        set.add(sender)
                                        saveBlockedSet(groupId, set)
                                        hideView(view, groupId, sender)
                                        return
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    WeLogger.e(TAG, "onCreateView error", e)
                }
            }
        })
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this); hiddenSendersCache.clear()
    }

    private fun hideView(view: View, groupId: String, sender: String) {
        synchronized(hiddenSendersCache) {
            hiddenSendersCache.getOrPut(groupId) { mutableMapOf() }
                .getOrPut(sender) { mutableListOf() }.add(view)
        }
        view.visibility = View.GONE
    }

    private fun blockedPrefKey(g: String) = "blocked_members_$g"
    private fun kwPrefKey(g: String) = "block_keywords_$g"
    private fun tgtPrefKey(g: String) = "block_keyword_targets_$g"

    private fun getBlockedSet(g: String): Set<String> = WePrefs.getStringSetOrDef(blockedPrefKey(g), emptySet())
    private fun saveBlockedSet(g: String, s: Set<String>) = WePrefs.putStringSet(blockedPrefKey(g), s)
    private fun getKeywords(g: String): Set<String> = WePrefs.getStringSetOrDef(kwPrefKey(g), emptySet())
    private fun saveKeywords(g: String, s: Set<String>) = WePrefs.putStringSet(kwPrefKey(g), s)
    private fun getKeywordTargets(g: String): Set<String> = WePrefs.getStringSetOrDef(tgtPrefKey(g), emptySet())
    private fun saveKeywordTargets(g: String, s: Set<String>) = WePrefs.putStringSet(tgtPrefKey(g), s)
    private fun getKeywordBlockEnabled(g: String): Boolean = WePrefs.getBoolOrDef(kwPrefKey(g) + "_enabled", false)
    private fun setKeywordBlockEnabled(g: String, v: Boolean) = WePrefs.putBool(kwPrefKey(g) + "_enabled", v)

    // 解除屏蔽：恢复可见 + 强制刷新父级 RecyclerView
    private fun unblockMember(g: String, wx: String) {
        val set = getBlockedSet(g).toMutableSet()
        set.remove(wx)
        saveBlockedSet(g, set)

        var parentToInvalidate: View? = null
        synchronized(hiddenSendersCache) {
            hiddenSendersCache[g]?.get(wx)?.forEach { view ->
                try {
                    view.visibility = View.VISIBLE
                    if (parentToInvalidate == null) {
                        var p = view.parent
                        while (p is View) {
                            if (p is androidx.recyclerview.widget.RecyclerView) {
                                parentToInvalidate = p
                                break
                            }
                            p = p.parent
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        // 强制 RecyclerView 重新布局，让新添加的消息也能正确渲染
        parentToInvalidate?.let { rv ->
            try {
                (rv as? androidx.recyclerview.widget.RecyclerView)?.apply {
                    // 重新 layout 让之前因为 GONE 而空出来的位置重新填充
                    post { requestLayout() }
                }
            } catch (_: Exception) { }
        }
    }

    // 屏蔽成员：保持缓存，重新隐藏已有 View
    private fun blockMembersNow(g: String, wxs: Set<String>) {
        val set = getBlockedSet(g).toMutableSet()
        set.addAll(wxs)
        saveBlockedSet(g, set)
        synchronized(hiddenSendersCache) {
            for (wx in wxs) {
                hiddenSendersCache[g]?.get(wx)?.forEach { view ->
                    try { view.visibility = View.GONE } catch (_: Exception) { }
                }
            }
        }
    }

    fun showBlockManager(context: Context, groupId: String) {
        showComposeDialog(context) {
            var tab by remember { mutableIntStateOf(0) }
            AlertDialogContent(
                title = { Text("群聊屏蔽功能", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.size(360.dp, 480.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(onClick = { tab = 0 }) { Text("屏蔽成员", fontSize = 13.sp, fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                            TextButton(onClick = { tab = 1 }) { Text("屏蔽关键词", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                        }
                        HorizontalDivider(); Spacer(Modifier.height(4.dp))
                        when (tab) { 0 -> BlockMemberTab(context, groupId); 1 -> KeywordTab(context, groupId) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }

    @Composable
    private fun BlockMemberTab(context: Context, groupId: String) {
        Column(Modifier.fillMaxWidth()) {
            var innerTab by remember { mutableIntStateOf(0) }
            val blockedSet = remember { mutableStateOf(getBlockedSet(groupId)) }
            val members = remember { mutableStateOf<List<WeContact>>(emptyList()) }
            var loaded by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val selectedToBlock = remember { mutableStateListOf<String>() }

            LaunchedEffect(Unit) {
                members.value = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                loaded = true
            }

            val filteredMembers = remember(searchQuery, members.value, blockedSet.value) {
                members.value.filter { it.wxId !in blockedSet.value && (searchQuery.isBlank() ||
                    (it.displayName.ifEmpty { it.nickname }).lowercase().contains(searchQuery.lowercase()) ||
                    it.wxId.lowercase().contains(searchQuery.lowercase())) }
            }

            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(onClick = { innerTab = 0 }) { Text("已屏蔽 (${blockedSet.value.size})", fontSize = 12.sp, fontWeight = if (innerTab == 0) FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { innerTab = 1 }) { Text("添加屏蔽", fontSize = 12.sp, fontWeight = if (innerTab == 1) FontWeight.Bold else FontWeight.Normal) }
            }
            HorizontalDivider(); Spacer(Modifier.height(4.dp))

            when (innerTab) {
                0 -> {
                    if (blockedSet.value.isEmpty()) { Text("暂无已屏蔽的成员", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(blockedSet.value.toList().sorted(), { it }) { wxId ->
                                val contact = try { WeDatabaseApi.getFriend(wxId) } catch (_: Exception) { null }
                                val name = contact?.displayName ?: contact?.nickname ?: wxId
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = contact?.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), imageLoader = GlobalImageLoader)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) { Text(name, fontSize = 14.sp); Text(wxId, fontSize = 11.sp, color = CColor.Gray) }
                                    TextButton(onClick = { unblockMember(groupId, wxId); blockedSet.value = getBlockedSet(groupId); showToast(context, "已解除屏蔽") }) { Text("解除", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (!loaded) { Text("加载成员中...", modifier = Modifier.padding(16.dp)) }
                    else if (members.value.isEmpty()) { Text("无法获取群成员列表", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索成员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("群成员 ${members.value.size} 人 | 已选 ${selectedToBlock.size}", fontSize = 11.sp, color = CColor.Gray)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(filteredMembers, { it.wxId }) { m ->
                                Row(Modifier.fillMaxWidth().clickable { if (m.wxId in selectedToBlock) selectedToBlock.remove(m.wxId) else selectedToBlock.add(m.wxId) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = m.wxId in selectedToBlock, onCheckedChange = { c -> if (c) selectedToBlock.add(m.wxId) else selectedToBlock.remove(m.wxId) })
                                    Spacer(Modifier.width(8.dp))
                                    AsyncImage(model = m.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), imageLoader = GlobalImageLoader)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) { Text(m.displayName.ifEmpty { m.nickname }, fontSize = 14.sp); Text(m.wxId, fontSize = 11.sp, color = CColor.Gray) }
                                }
                            }
                        }
                        if (selectedToBlock.isNotEmpty()) {
                            Button(onClick = {
                                blockMembersNow(groupId, selectedToBlock.toSet()); blockedSet.value = getBlockedSet(groupId)
                                val c = selectedToBlock.size; selectedToBlock.clear(); innerTab = 0; showToast(context, "已屏蔽 $c 人")
                            }, Modifier.fillMaxWidth()) { Text("屏蔽选中 (${selectedToBlock.size})", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KeywordTab(context: Context, groupId: String) {
        Column(Modifier.fillMaxWidth()) {
            var innerTab by remember { mutableIntStateOf(0) }
            val keywords = remember { mutableStateOf(getKeywords(groupId)) }
            val enabled = remember { mutableStateOf(getKeywordBlockEnabled(groupId)) }
            val members = remember { mutableStateOf<List<WeContact>>(emptyList()) }
            val keywordTargets = remember { mutableStateOf(getKeywordTargets(groupId)) }
            var loaded by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val selectedTargets = remember { mutableStateListOf<String>() }
            val selectedForAddKw = remember { mutableStateListOf<String>() }
            var newKeyword by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                members.value = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                loaded = true
            }

            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(onClick = { innerTab = 0 }) { Text("关键词列表 (${keywords.value.size})", fontSize = 12.sp, fontWeight = if (innerTab == 0) FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { innerTab = 1 }) { Text("添加关键词", fontSize = 12.sp, fontWeight = if (innerTab == 1) FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { innerTab = 2 }) { Text("监控对象", fontSize = 12.sp, fontWeight = if (innerTab == 2) FontWeight.Bold else FontWeight.Normal) }
            }
            HorizontalDivider(); Spacer(Modifier.height(4.dp))

            when (innerTab) {
                0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动屏蔽", fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                        Switch(checked = enabled.value, onCheckedChange = { enabled.value = it; setKeywordBlockEnabled(groupId, it); showToast(context, if (it) "关键词屏蔽已开启" else "关键词屏蔽已关闭") })
                    }
                    Spacer(Modifier.height(4.dp))
                    if (keywords.value.isEmpty()) { Text("暂无关键词", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(keywords.value.toList().sorted(), { it }) { kw ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔑 ", fontSize = 14.sp); Text(kw, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { val s = getKeywords(groupId).toMutableSet(); s.remove(kw); saveKeywords(groupId, s); keywords.value = s; showToast(context, "已删除关键词: $kw") }) { Text("删除", fontSize = 12.sp, color = CColor(0xFFF44336)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (enabled.value) {
                            val tc = keywordTargets.value.size; val tm = members.value.size
                            Text(if (tc == 0) "监控对象: 全部成员 ($tm 人)" else "监控对象: $tc/$tm 人", fontSize = 11.sp, color = CColor.Gray)
                        }
                    }
                }
                1 -> {
                    OutlinedTextField(value = newKeyword, onValueChange = { newKeyword = it }, label = { Text("输入关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    Spacer(Modifier.height(8.dp))
                    if (newKeyword.isNotBlank()) { Button(onClick = { val s = getKeywords(groupId).toMutableSet(); s.add(newKeyword.trim()); saveKeywords(groupId, s); keywords.value = s; newKeyword = ""; showToast(context, "已添加关键词") }) { Text("添加", fontSize = 13.sp) } }
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                    Text("也可批量从已屏蔽成员中添加", fontSize = 12.sp, color = CColor.Gray)
                    Spacer(Modifier.height(4.dp))
                    val blockedSet = getBlockedSet(groupId)
                    val blockedMembers = remember(blockedSet, members.value) { members.value.filter { it.wxId in blockedSet } }
                    if (blockedMembers.isEmpty()) { Text("暂无已屏蔽成员可提取关键词", fontSize = 12.sp, color = CColor.Gray, modifier = Modifier.padding(8.dp)) }
                    else {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索已屏蔽成员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val filteredBlocked = remember(searchQuery, blockedMembers) {
                            if (searchQuery.isBlank()) blockedMembers else blockedMembers.filter { it.displayName.lowercase().contains(searchQuery.lowercase()) || it.wxId.lowercase().contains(searchQuery.lowercase()) }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(filteredBlocked, { it.wxId }) { m ->
                                Row(Modifier.fillMaxWidth().clickable { if (m.wxId in selectedForAddKw) selectedForAddKw.remove(m.wxId) else selectedForAddKw.add(m.wxId) }.padding(vertical = 4.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = m.wxId in selectedForAddKw, onCheckedChange = { c -> if (c) selectedForAddKw.add(m.wxId) else selectedForAddKw.remove(m.wxId) })
                                    Spacer(Modifier.width(8.dp)); AsyncImage(model = m.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), imageLoader = GlobalImageLoader)
                                    Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(m.displayName.ifEmpty { m.nickname }, fontSize = 13.sp); Text(m.wxId, fontSize = 10.sp, color = CColor.Gray) }
                                }
                            }
                        }
                        if (selectedForAddKw.isNotEmpty()) {
                            Button(onClick = {
                                val ck = getKeywords(groupId).toMutableSet()
                                selectedForAddKw.forEach { wx -> val c = members.value.find { it.wxId == wx }; val kw = c?.nickname?.take(6) ?: wx.take(8); if (kw.isNotBlank()) ck.add(kw) }
                                saveKeywords(groupId, ck); keywords.value = ck; selectedForAddKw.clear(); showToast(context, "已添加关键词")
                            }, Modifier.fillMaxWidth()) { Text("以昵称为关键词添加 (${selectedForAddKw.size})", fontSize = 13.sp) }
                        }
                    }
                }
                2 -> {
                    if (!loaded) { Text("加载成员中...", modifier = Modifier.padding(16.dp)) }
                    else if (members.value.isEmpty()) { Text("无法获取群成员列表", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        Text("选择要监控的成员（不选则监控全部）", fontSize = 12.sp, color = CColor.Gray)
                        Spacer(Modifier.height(4.dp)); OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索成员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(onClick = { selectedTargets.addAll(members.value.map { it.wxId }); keywordTargets.value = selectedTargets.toSet() }) { Text("全选", fontSize = 12.sp) }
                            TextButton(onClick = { selectedTargets.clear(); keywordTargets.value = emptySet() }) { Text("清空(监控全部)", fontSize = 12.sp) }
                        }
                        val filteredMembers = remember(searchQuery, members.value) {
                            if (searchQuery.isBlank()) members.value else members.value.filter { it.displayName.lowercase().contains(searchQuery.lowercase()) || it.wxId.lowercase().contains(searchQuery.lowercase()) }
                        }
                        Spacer(Modifier.height(2.dp)); Text("已选 ${selectedTargets.size} 人", fontSize = 11.sp, color = CColor(0xFF4CAF50))
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(filteredMembers, { it.wxId }) { m ->
                                Row(Modifier.fillMaxWidth().clickable { if (m.wxId in selectedTargets) selectedTargets.remove(m.wxId) else selectedTargets.add(m.wxId) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = m.wxId in selectedTargets, onCheckedChange = { c -> if (c) selectedTargets.add(m.wxId) else selectedTargets.remove(m.wxId) })
                                    Spacer(Modifier.width(8.dp)); AsyncImage(model = m.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), imageLoader = GlobalImageLoader)
                                    Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(m.displayName.ifEmpty { m.nickname }, fontSize = 14.sp); Text(m.wxId, fontSize = 11.sp, color = CColor.Gray) }
                                }
                            }
                        }
                        Button(onClick = { saveKeywordTargets(groupId, selectedTargets.toSet()); keywordTargets.value = selectedTargets.toSet(); showToast(context, "已保存监控对象（${selectedTargets.size} 人）") }, Modifier.fillMaxWidth()) { Text("保存监控对象", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}