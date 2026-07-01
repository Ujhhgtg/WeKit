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
    description = "屏蔽群成员消息（手动选择）和屏蔽消息（关键词过滤）；长按群聊进入管理"
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
    private val keywordHiddenViews = mutableMapOf<String, MutableList<View>>()

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

                    // 功能1：屏蔽成员
                    if (sender in getBlockedSet(groupId)) {
                        cacheAndHide(view, groupId, sender)
                        return
                    }

                    // 功能2：关键词过滤（只隐藏消息，不屏蔽人）
                    if (getKeywordFilterEnabled(groupId)) {
                        val keywords = getFilterKeywords(groupId)
                        val targets = getFilterTargets(groupId)
                        if (keywords.isNotEmpty() && (targets.isEmpty() || sender in targets)) {
                            val rawContent = msgInfo.content
                            val actualContent = msgInfo.actualContent
                            val strippedContent = actualContent.substringAfter(":").substringAfter("\n").trim()
                            val textsToCheck = listOfNotNull(
                                actualContent.takeIf { it.isNotBlank() },
                                rawContent.takeIf { it.isNotBlank() },
                                strippedContent.takeIf { it.isNotBlank() }
                            ).distinct()
                            for (text in textsToCheck) {
                                for (kw in keywords) {
                                    if (text.contains(kw, ignoreCase = true)) {
                                        WeLogger.i(TAG, "keyword filter hide msg from $sender kw='$kw'")
                                        synchronized(keywordHiddenViews) {
                                            keywordHiddenViews.getOrPut(groupId) { mutableListOf() }.add(view)
                                        }
                                        // 只设 GONE，不改 layoutParams，恢复时 VISIBLE 即可正确显示
                                        view.visibility = View.GONE
                                        return
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        })
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
        hiddenSendersCache.clear()
        keywordHiddenViews.clear()
    }

    private fun cacheAndHide(view: View, groupId: String, sender: String) {
        synchronized(hiddenSendersCache) {
            hiddenSendersCache.getOrPut(groupId) { mutableMapOf() }
                .getOrPut(sender) { mutableListOf() }.add(view)
        }
        view.visibility = View.GONE
    }

    // ── Pref 读写 ──

    private fun blockedPrefKey(g: String) = "blocked_members_$g"
    private fun getBlockedSet(g: String): Set<String> = WePrefs.getStringSetOrDef(blockedPrefKey(g), emptySet())
    private fun saveBlockedSet(g: String, s: Set<String>) = WePrefs.putStringSet(blockedPrefKey(g), s)

    private fun filterKwPrefKey(g: String) = "filter_keywords_$g"
    private fun filterTgtPrefKey(g: String) = "filter_targets_$g"
    private fun filterEnabledPrefKey(g: String) = "filter_enabled_$g"

    private fun getFilterKeywords(g: String): Set<String> = WePrefs.getStringSetOrDef(filterKwPrefKey(g), emptySet())
    private fun saveFilterKeywords(g: String, s: Set<String>) = WePrefs.putStringSet(filterKwPrefKey(g), s)
    private fun getFilterTargets(g: String): Set<String> = WePrefs.getStringSetOrDef(filterTgtPrefKey(g), emptySet())
    private fun saveFilterTargets(g: String, s: Set<String>) = WePrefs.putStringSet(filterTgtPrefKey(g), s)
    private fun getKeywordFilterEnabled(g: String): Boolean = WePrefs.getBoolOrDef(filterEnabledPrefKey(g), false)
    private fun setKeywordFilterEnabled(g: String, v: Boolean) {
        WePrefs.putBool(filterEnabledPrefKey(g), v)
        if (!v) {
            // 关闭时恢复所有被关键词隐藏的 View，并清空缓存
            synchronized(keywordHiddenViews) {
                val views = keywordHiddenViews.remove(g)
                if (views != null) {
                    for (view in views) {
                        try { view.visibility = View.VISIBLE } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun unblockMember(g: String, wx: String) {
        val set = getBlockedSet(g).toMutableSet(); set.remove(wx); saveBlockedSet(g, set)
        synchronized(hiddenSendersCache) {
            hiddenSendersCache[g]?.get(wx)?.forEach { try { it.visibility = View.VISIBLE } catch (_: Exception) { } }
        }
    }

    private fun blockMembersNow(g: String, wxs: Set<String>) {
        val set = getBlockedSet(g).toMutableSet(); set.addAll(wxs); saveBlockedSet(g, set)
        synchronized(hiddenSendersCache) {
            for (wx in wxs) {
                hiddenSendersCache[g]?.get(wx)?.forEach { try { it.visibility = View.GONE } catch (_: Exception) { } }
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
                            TextButton(onClick = { tab = 1 }) { Text("屏蔽消息", fontSize = 13.sp, fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                        }
                        HorizontalDivider(); Spacer(Modifier.height(4.dp))
                        when (tab) { 0 -> BlockMemberTab(context, groupId); 1 -> KeywordFilterTab(context, groupId) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }

    // ═══════════════════════
    // 功能1：屏蔽成员
    // ═══════════════════════

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

    // ═══════════════════════
    // 功能2：屏蔽消息（关键词过滤）
    // ═══════════════════════

    @Composable
    private fun KeywordFilterTab(context: Context, groupId: String) {
        Column(Modifier.fillMaxWidth()) {
            var innerTab by remember { mutableIntStateOf(0) }
            val keywords = remember { mutableStateOf(getFilterKeywords(groupId)) }
            val enabled = remember { mutableStateOf(getKeywordFilterEnabled(groupId)) }
            val members = remember { mutableStateOf<List<WeContact>>(emptyList()) }
            val filterTargets = remember { mutableStateOf(getFilterTargets(groupId)) }
            var loaded by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            // 【修复】从 pref 中加载已保存的监控对象
            val selectedTargets = remember { mutableStateListOf<String>().also { it.addAll(getFilterTargets(groupId)) } }
            var newKeyword by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                val m = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                members.value = m; loaded = true
            }

            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                TextButton(onClick = { innerTab = 0 }) { Text("关键词 (${keywords.value.size})", fontSize = 12.sp, fontWeight = if (innerTab == 0) FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { innerTab = 1 }) { Text("添加关键词", fontSize = 12.sp, fontWeight = if (innerTab == 1) FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { innerTab = 2 }) { Text("监控对象", fontSize = 12.sp, fontWeight = if (innerTab == 2) FontWeight.Bold else FontWeight.Normal) }
            }
            HorizontalDivider(); Spacer(Modifier.height(4.dp))

            when (innerTab) {
                // ── 关键词列表 ──
                0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("关键词过滤", fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                        Switch(checked = enabled.value, onCheckedChange = {
                            enabled.value = it; setKeywordFilterEnabled(groupId, it)
                            showToast(context, if (it) "关键词过滤已开启" else "关键词过滤已关闭")
                        })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("匹配关键词的消息会被隐藏，发送者不会被屏蔽", fontSize = 10.sp, color = CColor.Gray)
                    Spacer(Modifier.height(4.dp))
                    if (keywords.value.isEmpty()) { Text("暂无关键词", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(keywords.value.toList().sorted(), { it }) { kw ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔑 ", fontSize = 14.sp); Text(kw, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        val s = getFilterKeywords(groupId).toMutableSet(); s.remove(kw)
                                        saveFilterKeywords(groupId, s); keywords.value = s; showToast(context, "已删除关键词: $kw")
                                    }) { Text("删除", fontSize = 12.sp, color = CColor(0xFFF44336)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (enabled.value) {
                            val tc = filterTargets.value.size; val tm = members.value.size
                            Text(if (tc == 0) "监控对象: 全部成员 ($tm 人)" else "监控对象: $tc/$tm 人", fontSize = 11.sp, color = CColor.Gray)
                        }
                    }
                }

                // ── 添加关键词 ──
                1 -> {
                    OutlinedTextField(value = newKeyword, onValueChange = { newKeyword = it }, label = { Text("输入关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    Spacer(Modifier.height(8.dp))
                    if (newKeyword.isNotBlank()) {
                        Button(onClick = {
                            val s = getFilterKeywords(groupId).toMutableSet(); s.add(newKeyword.trim())
                            saveFilterKeywords(groupId, s); keywords.value = s; newKeyword = ""; showToast(context, "已添加关键词")
                        }) { Text("添加", fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("消息内容包含关键词就会被隐藏，不区分大小写", fontSize = 10.sp, color = CColor.Gray)
                }

                // ── 监控对象 ──
                2 -> {
                    if (!loaded) { Text("加载成员中...", modifier = Modifier.padding(16.dp)) }
                    else if (members.value.isEmpty()) { Text("无法获取群成员列表", modifier = Modifier.padding(16.dp), color = CColor.Gray) }
                    else {
                        Text("选择要监控的成员（不选 = 监控全部成员）", fontSize = 12.sp, color = CColor.Gray)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索成员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            TextButton(onClick = { selectedTargets.addAll(members.value.map { it.wxId }); filterTargets.value = selectedTargets.toSet() }) { Text("全选", fontSize = 12.sp) }
                            TextButton(onClick = { selectedTargets.clear(); filterTargets.value = emptySet() }) { Text("清空(监控全部)", fontSize = 12.sp) }
                        }
                        val filteredMembers = remember(searchQuery, members.value) {
                            if (searchQuery.isBlank()) members.value else members.value.filter {
                                it.displayName.lowercase().contains(searchQuery.lowercase()) || it.wxId.lowercase().contains(searchQuery.lowercase())
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("已选 ${selectedTargets.size} 人", fontSize = 11.sp, color = CColor(0xFF4CAF50))
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(filteredMembers, { it.wxId }) { m ->
                                Row(Modifier.fillMaxWidth().clickable { if (m.wxId in selectedTargets) selectedTargets.remove(m.wxId) else selectedTargets.add(m.wxId) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = m.wxId in selectedTargets, onCheckedChange = { c -> if (c) selectedTargets.add(m.wxId) else selectedTargets.remove(m.wxId) })
                                    Spacer(Modifier.width(8.dp))
                                    AsyncImage(model = m.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)), imageLoader = GlobalImageLoader)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) { Text(m.displayName.ifEmpty { m.nickname }, fontSize = 14.sp); Text(m.wxId, fontSize = 11.sp, color = CColor.Gray) }
                                }
                            }
                        }
                        Button(onClick = {
                            saveFilterTargets(groupId, selectedTargets.toSet()); filterTargets.value = selectedTargets.toSet()
                            showToast(context, "已保存监控对象（${selectedTargets.size} 人）")
                        }, Modifier.fillMaxWidth()) { Text("保存监控对象", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}