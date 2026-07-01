package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Keyboard_arrow_right
import com.composables.icons.materialsymbols.outlined.Search
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
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
    description = "屏蔽群成员消息和关键词过滤消息；长按群聊进入管理"
)
object BlockGroupMemberMessages : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {
    private val TAG = nameOf(BlockGroupMemberMessages::class)

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777030,
                text = "屏蔽成员",
                drawable = CancelIcon,
                shouldShow = { ctx, _ -> ctx.talker.contains("@chatroom") },
                onClick = { ctx -> showBlockMemberScreen(ctx.activity, ctx.talker) }
            ),
            WeConversationContextMenuApi.MenuItem(
                id = 777031,
                text = "屏蔽消息（关键词）",
                drawable = CancelIcon,
                shouldShow = { ctx, _ -> ctx.talker.contains("@chatroom") },
                onClick = { ctx -> showKeywordFilterScreen(ctx.activity, ctx.talker) }
            )
        )
    }

    // ── 缓存 ──
    private val hiddenSendersCache = mutableMapOf<String, MutableMap<String, MutableList<View>>>()
    private val keywordHiddenViews = mutableMapOf<String, MutableList<Pair<View, String>>>()

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
        WeChatMessageViewApi.addListener(object : WeChatMessageViewApi.ICreateViewListener {
            override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
                try {
                    val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
                    val groupId = msgInfo.talker
                    if (!groupId.contains("@chatroom")) return
                    val sender = msgInfo.sender
                    if (sender.isEmpty() || sender == "system") return
                    if (sender in getBlockedSet(groupId)) { cacheAndHideSender(view, groupId, sender); return }
                    maybeHideByKeyword(view, msgInfo, groupId, sender)
                } catch (_: Exception) { }
            }
        })
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
        hiddenSendersCache.clear(); keywordHiddenViews.clear()
    }

    private fun maybeHideByKeyword(view: View, msgInfo: MessageInfo, groupId: String, sender: String) {
        if (!getKeywordFilterEnabled(groupId)) return
        val keywords = getFilterKeywords(groupId)
        val targets = getFilterTargets(groupId)
        if (keywords.isEmpty()) return
        if (targets.isNotEmpty() && sender !in targets) return
        val contentText = msgInfo.content
        val actualText = msgInfo.actualContent
        val strippedText = actualText.substringAfter(":").substringAfter("\n").trim()
        val textsToCheck = mutableListOf<String>()
        if (actualText.isNotBlank()) textsToCheck.add(actualText)
        if (contentText.isNotBlank() && contentText != actualText) textsToCheck.add(contentText)
        if (strippedText.isNotBlank() && strippedText != actualText && strippedText != contentText) textsToCheck.add(strippedText)
        for (text in textsToCheck) {
            for (kw in keywords) {
                if (text.contains(kw, ignoreCase = true)) {
                    synchronized(keywordHiddenViews) { keywordHiddenViews.getOrPut(groupId) { mutableListOf() }.add(view to text) }
                    view.visibility = View.GONE; return
                }
            }
        }
    }

    private fun cacheAndHideSender(view: View, groupId: String, sender: String) {
        synchronized(hiddenSendersCache) { hiddenSendersCache.getOrPut(groupId) { mutableMapOf() }.getOrPut(sender) { mutableListOf() }.add(view) }
        view.visibility = View.GONE
    }

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
        synchronized(keywordHiddenViews) {
            val entries = keywordHiddenViews[g] ?: return
            if (v) {
                val keywords = getFilterKeywords(g)
                for ((view, text) in entries) {
                    if (keywords.any { text.contains(it, ignoreCase = true) }) {
                        try { view.visibility = View.GONE } catch (_: Exception) { }
                    }
                }
            } else {
                for ((view, _) in entries) { try { view.visibility = View.VISIBLE } catch (_: Exception) { } }
            }
        }
    }

    private fun unblockMember(g: String, wx: String) {
        val set = getBlockedSet(g).toMutableSet(); set.remove(wx); saveBlockedSet(g, set)
        synchronized(hiddenSendersCache) { hiddenSendersCache[g]?.get(wx)?.forEach { try { it.visibility = View.VISIBLE } catch (_: Exception) { } } }
    }

    private fun blockMembersNow(g: String, wxs: Set<String>) {
        val set = getBlockedSet(g).toMutableSet(); set.addAll(wxs); saveBlockedSet(g, set)
        synchronized(hiddenSendersCache) { for (wx in wxs) { hiddenSendersCache[g]?.get(wx)?.forEach { try { it.visibility = View.GONE } catch (_: Exception) { } } } }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WeKit 风格全屏 UI 通用组件
    // ═══════════════════════════════════════════════════════════════

    @Composable
    private fun WeKitTopBar(title: String, onBack: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }

    @Composable
    private fun WeKitCategory(text: String) {
        Text(
            text = text, color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun WeKitRow(
        title: String,
        summary: String? = null,
        enabled: Boolean = true,
        showArrow: Boolean = false,
        trailing: @Composable (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.45f)
                .then(if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick
                ) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (!summary.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
            if (showArrow) { Icon(MaterialSymbols.Outlined.Keyboard_arrow_right, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    @Composable
    private fun WeKitDivider() {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }

    // ═══════════════════════════════════════════════════════════════
    //  功能1：屏蔽成员 — 全屏 WeKit 风格
    // ═══════════════════════════════════════════════════════════════

    fun showBlockMemberScreen(context: Context, groupId: String) {
        showComposeDialog(context) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
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

                    WeKitTopBar("屏蔽成员", onDismiss)

                    // 标签切换
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { innerTab = 0 }) {
                            Text("已屏蔽", fontWeight = if (innerTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (innerTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (blockedSet.value.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text("${blockedSet.value.size}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { innerTab = 1 }) {
                            Text("添加屏蔽", fontWeight = if (innerTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (innerTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        when (innerTab) {
                            0 -> {
                                if (blockedSet.value.isEmpty()) {
                                    WeKitCategory("暂无已屏蔽的成员")
                                } else {
                                    blockedSet.value.toList().sorted().forEach { wxId ->
                                        val contact = try { WeDatabaseApi.getFriend(wxId) } catch (_: Exception) { null }
                                        val name = contact?.displayName ?: contact?.nickname ?: wxId
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = contact?.avatarUrl, contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                                                imageLoader = GlobalImageLoader
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                                Text(wxId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            TextButton(onClick = {
                                                unblockMember(groupId, wxId); blockedSet.value = getBlockedSet(groupId)
                                                showToast(context, "已解除屏蔽")
                                            }) { Text("解除", fontSize = 12.sp) }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                if (!loaded) {
                                    WeKitCategory("加载成员中...")
                                } else if (members.value.isEmpty()) {
                                    WeKitCategory("无法获取群成员列表")
                                } else {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("搜索成员") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) }
                                    )
                                    Spacer(Modifier.height(4.dp))

                                    val filteredMembers = remember(searchQuery, members.value, blockedSet.value) {
                                        if (searchQuery.isBlank()) members.value.filter { it.wxId !in blockedSet.value }
                                        else members.value.filter { it.wxId !in blockedSet.value &&
                                            ((it.displayName.ifEmpty { it.nickname }).lowercase().contains(searchQuery.lowercase()) || it.wxId.lowercase().contains(searchQuery.lowercase())) }
                                    }

                                    Text("群成员 ${members.value.size} 人 | 已选 ${selectedToBlock.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp))

                                    // 全选
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                        TextButton(onClick = {
                                            selectedToBlock.clear(); selectedToBlock.addAll(filteredMembers.map { it.wxId })
                                        }) { Text("全选", fontSize = 12.sp) }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { selectedToBlock.clear() }) { Text("取消全选", fontSize = 12.sp) }
                                    }

                                    filteredMembers.forEach { m ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                if (m.wxId in selectedToBlock) selectedToBlock.remove(m.wxId) else selectedToBlock.add(m.wxId)
                                            }.padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(checked = m.wxId in selectedToBlock,
                                                onCheckedChange = { c -> if (c) selectedToBlock.add(m.wxId) else selectedToBlock.remove(m.wxId) })
                                            Spacer(Modifier.width(4.dp))
                                            AsyncImage(model = m.avatarUrl, contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                                imageLoader = GlobalImageLoader)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(m.displayName.ifEmpty { m.nickname }, style = MaterialTheme.typography.bodyLarge)
                                                Text(m.wxId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (selectedToBlock.isNotEmpty()) {
                                        Button(
                                            onClick = {
                                                blockMembersNow(groupId, selectedToBlock.toSet())
                                                blockedSet.value = getBlockedSet(groupId)
                                                val c = selectedToBlock.size; selectedToBlock.clear(); innerTab = 0
                                                showToast(context, "已屏蔽 $c 人")
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                        ) { Text("屏蔽选中 (${selectedToBlock.size})") }
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  功能2：屏蔽消息（关键词）— 全屏 WeKit 风格
    // ═══════════════════════════════════════════════════════════════

    fun showKeywordFilterScreen(context: Context, groupId: String) {
        showComposeDialog(context) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                val keywords = remember { mutableStateOf(getFilterKeywords(groupId)) }
                val enabled = remember { mutableStateOf(getKeywordFilterEnabled(groupId)) }
                val members = remember { mutableStateOf<List<WeContact>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                val selectedTargets = remember { mutableStateListOf<String>().also { it.addAll(getFilterTargets(groupId)) } }
                var newKeyword by remember { mutableStateOf("") }
                var showAddKw by remember { mutableStateOf(false) }
                var showTargetPicker by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val m = try { WeDatabaseApi.getGroupMembers(groupId) } catch (_: Exception) { emptyList() }
                    members.value = m; loaded = true
                }

                if (showTargetPicker) {
                    // 监控对象选择页面
                    Column(Modifier.fillMaxSize()) {
                        WeKitTopBar("选择监控对象", { showTargetPicker = false })
                        if (!loaded) { WeKitCategory("加载成员中...") }
                        else if (members.value.isEmpty()) { WeKitCategory("无法获取群成员列表") }
                        else {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text("不选 = 监控全部成员", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                    TextButton(onClick = { selectedTargets.clear(); selectedTargets.addAll(members.value.map { it.wxId }) }) { Text("全选", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { selectedTargets.clear() }) { Text("清空(监控全部)", fontSize = 12.sp) }
                                }
                                Text("已选 ${selectedTargets.size} 人", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                                members.value.forEach { m ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (m.wxId in selectedTargets) selectedTargets.remove(m.wxId) else selectedTargets.add(m.wxId)
                                        }.padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = m.wxId in selectedTargets,
                                            onCheckedChange = { c -> if (c) selectedTargets.add(m.wxId) else selectedTargets.remove(m.wxId) })
                                        Spacer(Modifier.width(4.dp))
                                        AsyncImage(model = m.avatarUrl, contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                            imageLoader = GlobalImageLoader)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(m.displayName.ifEmpty { m.nickname }, style = MaterialTheme.typography.bodyLarge)
                                            Text(m.wxId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        saveFilterTargets(groupId, selectedTargets.toSet())
                                        showToast(context, "已保存监控对象（${selectedTargets.size} 人）")
                                        showTargetPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                ) { Text("保存监控对象（${selectedTargets.size} 人）") }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                } else {
                    // 主页面
                    Column(Modifier.fillMaxSize()) {
                        WeKitTopBar("屏蔽消息（关键词）", onDismiss)

                        // 行1：开关
                        WeKitRow(
                            title = "关键词过滤",
                            summary = if (enabled.value) "已开启" else "已关闭",
                            trailing = {
                                Switch(checked = enabled.value, onCheckedChange = {
                                    enabled.value = it; setKeywordFilterEnabled(groupId, it)
                                    showToast(context, if (it) "关键词过滤已开启" else "关键词过滤已关闭")
                                })
                            }
                        )
                        WeKitDivider()

                        // 行2：监控对象
                        val tc = selectedTargets.size; val tm = members.value.size
                        WeKitRow(
                            title = "监控对象",
                            summary = if (tc == 0) "全部成员 ($tm 人)" else "$tc/$tm 人",
                            showArrow = true,
                            onClick = { showTargetPicker = true }
                        )
                        WeKitDivider()

                        // 行3~N：关键词列表
                        WeKitCategory("关键词")
                        if (keywords.value.isEmpty()) {
                            Text("暂无关键词", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                keywords.value.toList().sorted().forEach { kw ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔑", fontSize = 14.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(kw, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            val s = getFilterKeywords(groupId).toMutableSet(); s.remove(kw)
                                            saveFilterKeywords(groupId, s); keywords.value = s; showToast(context, "已删除: $kw")
                                        }) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }

                        // 底部添加按钮
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showAddKw) {
                                OutlinedTextField(
                                    value = newKeyword, onValueChange = { newKeyword = it },
                                    placeholder = { Text("输入关键词") },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    if (newKeyword.isNotBlank()) {
                                        val s = getFilterKeywords(groupId).toMutableSet(); s.add(newKeyword.trim())
                                        saveFilterKeywords(groupId, s); keywords.value = s; newKeyword = ""
                                        showToast(context, "已添加关键词"); showAddKw = false
                                    }
                                }) { Text("添加", fontSize = 13.sp) }
                            } else {
                                TextButton(onClick = { showAddKw = true }) { Text("+ 添加关键词", fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}